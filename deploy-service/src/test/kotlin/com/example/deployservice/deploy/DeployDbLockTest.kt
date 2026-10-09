package com.example.deployservice.deploy

import com.example.deployservice.api.ApiException
import com.example.deployservice.api.UpstreamException
import com.example.deployservice.config.DeployProperties
import com.example.deployservice.config.DeployPropertiesConfig
import com.example.deployservice.config.RoJpaConfig
import com.example.deployservice.config.RwJpaConfig
import com.example.deployservice.deploy.DeployProps.IMAGE
import com.example.deployservice.deploy.lock.PessimisticLock
import com.example.deployservice.deploy.rw.DeploymentRwRepository
import com.example.deployservice.gateway.ArgoOperationState
import com.example.deployservice.gateway.DeploymentSnapshot
import com.example.deployservice.gateway.GitHubGateway
import com.example.deployservice.gateway.RepoCommit
import com.example.deployservice.gateway.RepoFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * DB 잠금(modu-platform.pessimistic_lock) 으로 지키는 두 규칙을 실제 JPA·트랜잭션(H2, MODE=MySQL)으로 본다.
 * 1) 서비스당 RUNNING 하나 — 동시 시작 요청 중 하나만 들어가고 나머지는 409.
 * 2) 인프라 커밋 구간(sha 읽기 → PUT)은 서비스가 달라도 겹치지 않는다.
 * 파드 둘은 같은 DB 를 보는 스레드 여럿으로 흉내 낸다(잠금은 DB 에 있으므로 JVM 안의 동기화와 무관하다).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    RwJpaConfig::class, RoJpaConfig::class, JacksonAutoConfiguration::class, DeployPropertiesConfig::class,
    JpaDeploymentStore::class, PessimisticLock::class, JpaInfraCommitLock::class, DeployDbLockTest.Config::class,
)
class DeployDbLockTest(
    @Autowired private val store: JpaDeploymentStore,
    @Autowired private val repository: DeploymentRwRepository,
    @Autowired private val pessimisticLock: PessimisticLock,
    @Autowired private val commitLock: JpaInfraCommitLock,
) {

    @TestConfiguration
    class Config {
        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }

    @AfterEach
    fun clean() = repository.deleteAllInBatch()

    private val gatewayImage = "ghcr.io/tear94fall/modu-platform/gateway-service"
    private val props = DeployProps.props.copy(
        services = DeployProps.props.services + DeployProperties.ServiceSpec("gateway-service", "modu_platform", gatewayImage),
    )
    private val kustomization = """
        |images:
        |  - name: $IMAGE
        |    newTag: develop-35db83f
        |  - name: $gatewayImage
        |    newTag: develop-1111111
        |""".trimMargin()

    private fun record(id: String, service: String = "point-service", tag: String = "develop-5708871") =
        DeploymentRecord(id = id, service = service, tag = tag, by = "me", startedAt = Instant.now())

    @Test
    fun `pessimistic lock needs an RW transaction`() {
        assertThrows<IllegalTransactionStateException> { pessimisticLock.withLock("x") { 1 } }
    }

    @Test
    fun `concurrent starts of the same service leave exactly one RUNNING record and the rest get 409`() {
        val n = 6
        // 실행기는 돌지 않게(풀에 넣기만) — RUNNING 이 계속 남아 있어야 판단이 보인다.
        val parked = CountDownLatch(1)
        val pool = Executors.newCachedThreadPool()
        val executor = DeploymentExecutor(props, FakeGitHub(kustomization), FakeArgo(), FakeKubernetes(), store)
        val runner = DeploymentRunner(store, executor, Executors.newSingleThreadExecutor().also { it.submit { parked.await() } })
        val barrier = CyclicBarrier(n)
        try {
            val results = (1..n).map { i ->
                pool.submit(Callable {
                    barrier.await()
                    runCatching { runner.start(record("dep-$i")) }
                })
            }.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it.isSuccess })
            val conflicts = results.mapNotNull { it.exceptionOrNull() }
            assertEquals(n - 1, conflicts.size)
            conflicts.forEach {
                assertTrue(it is ApiException && it.error == "deploy_in_progress" && it.status.value() == 409, it.toString())
            }
            val running = repository.findByStatus(DeploymentStatus.RUNNING.name)
            assertEquals(1, running.size)
            assertEquals(results.single { it.isSuccess }.getOrThrow().id, running.single().id)
            assertTrue(store.hasRunning("point-service"))
            // 다른 서비스는 막지 않는다
            runner.start(record("dep-gw", service = "gateway-service"))
            assertEquals(2, repository.findByStatus(DeploymentStatus.RUNNING.name).size)
        } finally {
            parked.countDown()
            runner.shutdown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `commit steps of different services never overlap and both succeed`() {
        val github = SerialCheckingGitHub(kustomization)
        store.add(record("dep-a", service = "point-service", tag = "develop-aaaaaaa"))
        store.add(record("dep-b", service = "gateway-service", tag = "develop-bbbbbbb"))
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            listOf("dep-a" to "point-service", "dep-b" to "gateway-service").map { (id, service) ->
                pool.submit {
                    val clock = MutableClock()
                    val argo = FakeArgo().apply {
                        pendingRevision = "c0ffee1234567890"
                        phases += ArgoOperationState("Succeeded", "ok", clock.instant(), "c0ffee1234567890")
                    }
                    val k8s = FakeKubernetes().apply {
                        snapshots += DeploymentSnapshot(service, 2, 2, 1, 1, 1, 1, 1, "x", "True", "True")
                    }
                    val executor = DeploymentExecutor(props, github, argo, k8s, store, clock, { clock.advance(it) }, Timeouts(), commitLock)
                    barrier.await()
                    executor.run(id)
                }
            }.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(emptyList<String>(), github.violations)
        assertEquals(2, github.puts.get())
        assertEquals(DeploymentStatus.SUCCEEDED, store.get("dep-a")!!.status, store.get("dep-a")!!.error)
        assertEquals(DeploymentStatus.SUCCEEDED, store.get("dep-b")!!.status, store.get("dep-b")!!.error)
        assertTrue(github.content.contains("newTag: develop-aaaaaaa"), github.content)
        assertTrue(github.content.contains("newTag: develop-bbbbbbb"), github.content)
        assertEquals("develop-35db83f", store.get("dep-a")!!.previousTag)
        assertEquals("develop-1111111", store.get("dep-b")!!.previousTag)
    }

    /**
     * GitHub contents API 흉내: PUT 의 sha 가 지금 sha 와 다르면 409(실제처럼). 그리고 읽기(getFile)부터 PUT 까지를 한 구간으로 보고
     * 두 스레드가 그 구간에 함께 있으면 위반으로 적는다. 구간을 일부러 길게(150ms) 잡아 겹칠 기회를 준다.
     */
    class SerialCheckingGitHub(@Volatile var content: String) : GitHubGateway {
        private val inside = AtomicInteger()
        private val sha = AtomicInteger(1)
        val puts = AtomicInteger()
        val violations: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun getFile(repo: String, branch: String, path: String): RepoFile {
            if (inside.incrementAndGet() > 1) violations += "overlap at getFile on ${Thread.currentThread().name}"
            Thread.sleep(150)
            synchronized(this) { return RepoFile("blob-${sha.get()}", content) }
        }

        override fun putFile(repo: String, branch: String, path: String, content: String, sha: String, message: String): RepoCommit {
            try {
                Thread.sleep(50)
                synchronized(this) {
                    if (sha != "blob-${this.sha.get()}") throw UpstreamException("github", "GitHub 파일 커밋 실패 (409): sha mismatch", null, 409)
                    this.content = content
                    this.sha.incrementAndGet()
                    puts.incrementAndGet()
                }
                return RepoCommit("c0ffee1234567890", "https://github.com/tear94fall/modu_infra/commit/c0ffee1234567890")
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun branchHead(repo: String, branch: String) = RepoCommit("head000000000000", "")
        override fun containerVersions(packageName: String) = emptyList<ContainerVersion>()
        override fun commitMessage(repo: String, sha: String): String? = null
    }
}
