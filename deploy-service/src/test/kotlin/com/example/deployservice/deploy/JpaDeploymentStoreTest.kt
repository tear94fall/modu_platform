package com.example.deployservice.deploy

import com.example.deployservice.config.DeployPropertiesConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import com.example.deployservice.config.RoJpaConfig
import com.example.deployservice.config.RwJpaConfig
import com.example.deployservice.deploy.ro.DeploymentRoRepository
import com.example.deployservice.deploy.rw.DeploymentRwRepository
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 운영 저장소(JPA, RW/RO 분리 설정 그대로) — H2(MODE=MySQL). config/application.yml 의 master·replica 는 같은 H2 DB 라 replica 쪽 읽기가
 * master 쓰기를 본다(복제 지연 0). DDL 은 RW 의 ddl-auto: update 로 엔티티에서 만든다(운영은 DBA 의 platform.modu-platform.sql + validate).
 *
 * 테스트 트랜잭션은 끈다 — RW 트랜잭션 안의 미커밋 행은 RO(다른 연결)에서 안 보이므로 실제처럼 메서드마다 커밋하고, 끝나면 표를 비운다.
 */
@DataJpaTest(properties = ["deploy.store.capacity=5"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    RwJpaConfig::class, RoJpaConfig::class, JacksonAutoConfiguration::class, DeployPropertiesConfig::class,
    JpaDeploymentStore::class, StaleDeploymentRecovery::class, JpaDeploymentStoreTest.ClockConfig::class,
)
class JpaDeploymentStoreTest(
    @Autowired private val store: JpaDeploymentStore,
    @Autowired private val repository: DeploymentRwRepository,
    @Autowired private val roRepository: DeploymentRoRepository,
    @Autowired private val recovery: StaleDeploymentRecovery,
) {

    @AfterEach
    fun clean() = repository.deleteAllInBatch()

    @Test
    fun `RW and RO repositories are separate persistence units`() {
        store.add(record("x"))
        // RO 쪽(replica 풀)에서도 커밋된 행이 보인다 — 테스트에선 같은 H2 DB.
        assertEquals(1L, roRepository.count())
        assertEquals("x", roRepository.findAllByOrderByStartedAtDescCreatedAtDesc(PageRequest.of(0, 1)).single().id)
    }

    @TestConfiguration
    class ClockConfig {
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-07T09:00:00Z"), ZoneOffset.UTC)
    }

    private val t0 = Instant.parse("2026-10-07T08:00:00.123456Z")

    private fun record(id: String, service: String = "point-service", status: DeploymentStatus = DeploymentStatus.RUNNING, previous: String? = null, minute: Long = 0) =
        DeploymentRecord(
            id = id, service = service, tag = "develop-$id", previousTag = previous, by = "임준섭", byId = "104614857372392207989",
            startedAt = t0.plusSeconds(60 * minute), status = status,
        )

    @Test
    fun `add then get maps every column back`() {
        val r = record("dep-20261007-080000-0001", previous = "develop-35db83f")
        store.add(r)

        assertEquals(r, store.get(r.id))
        val e = repository.findById(r.id).get()
        assertEquals("임준섭", e.byName)
        assertEquals("104614857372392207989", e.byId)
        assertEquals("RUNNING", e.status)
        assertEquals("COMMIT", e.step)
        assertEquals(Progress.COMMIT_START, e.percent)
        assertEquals(t0, e.startedAt) // datetime(6): 마이크로초까지
        assertNull(e.rolloutJson)
        assertTrue(e.stepsJson.startsWith("[{"), e.stepsJson)
        assertNotNull(e.createdAt)
        assertEquals(e.createdAt, e.updatedAt)
        assertNull(store.get("nope"))
        assertEquals(1, store.size())
    }

    @Test
    fun `steps and rollout survive the JSON round trip`() {
        store.add(record("a"))
        val at = Instant.parse("2026-10-07T08:01:02.345678Z")

        val updated = store.update("a") {
            it.copy(
                status = DeploymentStatus.SUCCEEDED, step = Step.DONE, percent = 100, finishedAt = at,
                commit = CommitView("c0ffee1234567890", "https://github.com/tear94fall/modu_infra/commit/c0ffee1234567890"),
                rollout = RolloutView(2, 2, 2, 2, listOf(PodView("point-service-1", "Running", true, ""), PodView("point-service-2", "Pending", false, "ContainerCreating"))),
                error = null,
            ).withStep(Step.COMMIT) { s -> s.copy(status = StepStatus.SUCCEEDED, message = "커밋 c0ffee1", startedAt = at, finishedAt = at.plusSeconds(1)) }
                .withStep(Step.ROLLOUT) { s -> s.copy(status = StepStatus.RUNNING, message = "준비 2/2", startedAt = at) }
        }!!

        val r = store.get("a")!!
        assertEquals(updated, r)
        assertEquals(StepStatus.SUCCEEDED, r.steps[0].status)
        assertEquals("커밋 c0ffee1", r.steps[0].message)
        assertEquals(at, r.steps[0].startedAt)
        assertEquals(at.plusSeconds(1), r.steps[0].finishedAt)
        assertEquals(StepStatus.PENDING, r.steps[1].status)
        assertEquals(2, r.rollout?.pods?.size)
        assertEquals("ContainerCreating", r.rollout?.pods?.get(1)?.reason)
        assertEquals("c0ffee1234567890", r.commit?.sha)
        val e = repository.findById("a").get()
        assertTrue(e.rolloutJson!!.startsWith("{\"desired\":2"), e.rolloutJson)
        assertTrue(e.stepsJson.contains("\"startedAt\":\"2026-10-07T08:01:02.345678Z\""), e.stepsJson)
        assertEquals("c0ffee1234567890", e.commitSha)
        assertNull(store.update("zzz") { it })
    }

    @Test
    fun `update is read-modify-write and keeps unrelated fields`() {
        store.add(record("a", previous = "develop-0000001"))

        store.update("a") { it.copy(percent = 42).withStep(Step.SYNC) { s -> s.copy(status = StepStatus.RUNNING) } }
        store.update("a") { it.withStep(Step.SYNC) { s -> s.copy(message = "동기화 중") } }

        val r = store.get("a")!!
        assertEquals(42, r.percent)
        assertEquals("develop-0000001", r.previousTag)
        assertEquals(StepStatus.RUNNING, r.steps[1].status)
        assertEquals("동기화 중", r.steps[1].message)
    }

    @Test
    fun `lists newest first, filters by service, caps the limit`() {
        (1..8).forEach { store.add(record("%02d".format(it), service = if (it % 2 == 0) "gateway-service" else "point-service", minute = it.toLong())) }

        assertEquals(listOf("08", "07", "06", "05", "04"), store.list().map { it.id }) // capacity 5
        assertEquals(listOf("08", "07"), store.list(limit = 2).map { it.id })
        assertEquals(listOf("08"), store.list(limit = 0).map { it.id }) // 최소 1
        assertEquals(listOf("07", "05", "03", "01"), store.list("point-service").map { it.id })
        assertEquals("08", store.latest("gateway-service")?.id)
        assertEquals(8, store.size())
        assertTrue(store.list("nope").isEmpty())
    }

    @Test
    fun `latestSucceeded picks the newest SUCCEEDED of that service only`() {
        store.add(record("a", status = DeploymentStatus.SUCCEEDED, previous = "develop-0000001", minute = 1))
        store.add(record("b", service = "gateway-service", status = DeploymentStatus.SUCCEEDED, previous = "develop-0000002", minute = 2))
        store.add(record("c", status = DeploymentStatus.FAILED, previous = "develop-0000003", minute = 3))
        store.add(record("d", minute = 4))

        assertEquals("a", store.latestSucceeded("point-service")?.id)
        assertEquals("b", store.latestSucceeded("gateway-service")?.id)
        assertNull(store.latestSucceeded("nope"))
        store.update("c") { it.copy(status = DeploymentStatus.SUCCEEDED) }
        assertEquals("c", store.latestSucceeded("point-service")?.id)
    }

    @Test
    fun `startup closes RUNNING rows as FAILED with the restart message`() {
        store.add(record("running", minute = 1))
        store.update("running") { it.copy(step = Step.SYNC, percent = 35).withStep(Step.COMMIT) { s -> s.copy(status = StepStatus.SUCCEEDED) }.withStep(Step.SYNC) { s -> s.copy(status = StepStatus.RUNNING) } }
        store.add(record("done", status = DeploymentStatus.SUCCEEDED, minute = 2))
        store.add(record("failed", status = DeploymentStatus.FAILED, minute = 3))

        recovery.run(DefaultApplicationArguments())

        val r = store.get("running")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertEquals(StaleDeploymentRecovery.MESSAGE, r.error)
        assertEquals(Instant.parse("2026-10-07T09:00:00Z"), r.finishedAt)
        assertEquals(Step.SYNC, r.step)
        assertEquals(35, r.percent)
        assertEquals(listOf(StepStatus.SUCCEEDED, StepStatus.FAILED, StepStatus.FAILED), r.steps.map { it.status })
        assertEquals(StaleDeploymentRecovery.MESSAGE, r.steps[1].message)
        assertEquals(Instant.parse("2026-10-07T09:00:00Z"), r.steps[1].finishedAt)
        assertNull(r.steps[2].message)
        assertEquals(DeploymentStatus.SUCCEEDED, store.get("done")!!.status)
        assertEquals(DeploymentStatus.FAILED, store.get("failed")!!.status)
        assertNull(store.get("failed")!!.error)
        assertTrue(repository.findByStatus("RUNNING").isEmpty())
    }
}
