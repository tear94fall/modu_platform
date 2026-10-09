package com.example.deployservice.deploy

import com.example.deployservice.api.ApiException
import com.example.deployservice.config.DeployProperties
import com.example.deployservice.deploy.ro.DeploymentRoRepository
import com.example.deployservice.deploy.rw.DeploymentRwRepository
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "서비스당 배포 하나" 수문장. **진짜 유일성은 DB 가 지킨다** — `deployment` 의 생성 열 `running_service`
 * (`CASE WHEN status = 'RUNNING' THEN service END`)와 유니크 키 `uk_deployment_running_service`.
 * dev MySQL 에서 두 번째 RUNNING INSERT 가 `Duplicate entry '<service>' for key 'deployment.uk_deployment_running_service'` 로
 * 거절되는 것을 확인했다. H2(테스트 DB)는 생성 열을 흉내 낼 수 없으므로 여기서는 그걸 증명하지 않고,
 * **DB 가 거절했을 때 앱이 어떻게 행동하는지**만 본다: [DataIntegrityViolationException] → false → 러너의 409 deploy_in_progress.
 */
class DeployStartGuardTest {

    private val rw: DeploymentRwRepository = mock()
    private val ro: DeploymentRoRepository = mock()
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /**
     * 트랜잭션은 흉내만 낸다(열고 닫는 척). 저장소 안의 TransactionTemplate 은 블록을 실행하고 예외를 그대로 올리므로
     * 이 테스트가 보려는 것 — 제약 위반을 `executeWithoutResult` **밖**에서 잡는다 — 이 그대로 드러난다.
     */
    private val txManager = object : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) = Unit
        override fun rollback(status: TransactionStatus) = Unit
    }

    private val store = JpaDeploymentStore(rw, ro, objectMapper, DeployProperties(), txManager)

    private fun record(id: String, service: String = "point-service") =
        DeploymentRecord(id = id, service = service, tag = "develop-5708871", by = "me", startedAt = Instant.parse("2026-10-09T08:00:00Z"))

    private fun noneRunning() = whenever(rw.existsByServiceAndStatus(any(), eq(DeploymentStatus.RUNNING.name))).thenReturn(false)

    @Test
    fun `an insert rejected by the unique key becomes false`() {
        noneRunning()
        whenever(rw.saveAndFlush(any())).thenThrow(
            DataIntegrityViolationException("Duplicate entry 'point-service' for key 'deployment.uk_deployment_running_service'"),
        )

        assertFalse(store.addIfNoneRunning(record("dep-2")))
    }

    @Test
    fun `the runner turns that rejection into 409 deploy_in_progress`() {
        noneRunning()
        whenever(rw.saveAndFlush(any())).thenThrow(
            DataIntegrityViolationException("Duplicate entry 'point-service' for key 'deployment.uk_deployment_running_service'"),
        )
        val runner = DeploymentRunner(store, mock())

        val e = assertThrows<ApiException> { runner.start(record("dep-2")) }

        assertEquals("deploy_in_progress", e.error)
        assertEquals(409, e.status.value())
        assertFalse(runner.isRunningLocally("point-service")) // 예약조차 되지 않았다
        runner.shutdown()
    }

    @Test
    fun `an insert that goes through returns true`() {
        noneRunning()
        whenever(rw.saveAndFlush(any())).thenAnswer { it.arguments[0] }

        assertTrue(store.addIfNoneRunning(record("dep-1")))
        verify(rw).saveAndFlush(any())
    }

    @Test
    fun `an already RUNNING record short-circuits before the insert`() {
        whenever(rw.existsByServiceAndStatus("point-service", DeploymentStatus.RUNNING.name)).thenReturn(true)

        assertFalse(store.addIfNoneRunning(record("dep-2")))
        verify(rw, never()).saveAndFlush(any())
    }

    @Test
    fun `any other DB failure propagates instead of looking like a conflict`() {
        noneRunning()
        whenever(rw.saveAndFlush(any())).thenThrow(RecoverableDataAccessException("Connection refused"))

        val e = assertThrows<RecoverableDataAccessException> { store.addIfNoneRunning(record("dep-1")) }
        assertEquals("Connection refused", e.message)
    }

    /**
     * 서로 다른 서비스는 공유 잠금 없이 나란히 시작한다(예전엔 `deploy:service:<name>` 비관적 잠금을 쓰면서도 서비스마다 달랐고,
     * 인프라 커밋은 하나짜리 잠금으로 묶였다 — 지금은 둘 다 없다). 유니크 키는 서비스 이름별이라 서로를 막지 않는다.
     */
    @Test
    fun `different services start concurrently`() {
        val services = (1..8).map { "svc-$it" }
        noneRunning()
        // 여덟 INSERT 가 모두 동시에 저장소 안에 들어와야 통과한다. 어딘가에서 직렬화되면 장벽이 안 모여 TimeoutException → 시작 실패.
        val barrier = CyclicBarrier(services.size)
        whenever(rw.saveAndFlush(any())).thenAnswer { barrier.await(10, TimeUnit.SECONDS); it.arguments[0] }
        val runner = DeploymentRunner(store, mock())
        val pool = Executors.newFixedThreadPool(services.size)
        try {
            val results = services.mapIndexed { i, service ->
                pool.submit { runner.start(record("dep-$i", service)) }
            }.map { runCatching { it.get(20, TimeUnit.SECONDS) } }

            assertEquals(services.size, results.count { it.isSuccess }, results.toString())
        } finally {
            runner.shutdown()
            pool.shutdownNow()
        }
    }
}
