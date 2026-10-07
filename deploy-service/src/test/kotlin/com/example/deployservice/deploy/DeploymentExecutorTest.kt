package com.example.deployservice.deploy

import com.example.deployservice.config.DeployProperties
import com.example.deployservice.deploy.DeployProps.props
import com.example.deployservice.gateway.ArgoApplicationState
import com.example.deployservice.gateway.ArgoCdGateway
import com.example.deployservice.gateway.ArgoOperationState
import com.example.deployservice.gateway.ArgoResource
import com.example.deployservice.gateway.DeploymentSnapshot
import com.example.deployservice.gateway.GitHubGateway
import com.example.deployservice.gateway.KubernetesGateway
import com.example.deployservice.gateway.PodSnapshot
import com.example.deployservice.gateway.RepoCommit
import com.example.deployservice.gateway.RepoFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque

/** 테스트 공통 설정. */
object DeployProps {
    const val IMAGE = "ghcr.io/tear94fall/modu-chat/point-service"
    val props = DeployProperties(
        github = DeployProperties.GitHub(owner = "tear94fall", infraRepo = "modu_infra", infraBranch = "main", kustomizationPath = "k8s/overlays/dev/kustomization.yaml", token = "t"),
        argocd = DeployProperties.ArgoCd(url = "http://argo", application = "modu-dev", token = "t"),
        kubernetes = DeployProperties.Kubernetes("modu"),
        services = listOf(DeployProperties.ServiceSpec("point-service", "modu_chat", IMAGE)),
    )
}

/** 잠 대신 시계를 돌린다. 제한 시간 검사가 결정적으로 돈다. */
class MutableClock(private var now: Instant = Instant.parse("2026-10-06T12:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId) = this
    fun advance(d: Duration) { now = now.plus(d) }
}

class FakeGitHub(var content: String) : GitHubGateway {
    val puts = mutableListOf<Triple<String, String, String>>() // content, sha, message
    var blobSha = "blob-1"
    var failPut: String? = null

    override fun getFile(repo: String, branch: String, path: String) = RepoFile(blobSha, content)
    override fun putFile(repo: String, branch: String, path: String, content: String, sha: String, message: String): RepoCommit {
        failPut?.let { throw IllegalStateException(it) }
        puts += Triple(content, sha, message)
        this.content = content
        blobSha = "blob-${puts.size + 1}"
        return RepoCommit("c0ffee1234567890", "https://github.com/tear94fall/modu_infra/commit/c0ffee1234567890")
    }
    override fun branchHead(repo: String, branch: String) = RepoCommit("head000000000000", "https://github.com/tear94fall/modu_infra/commit/head000000000000")
    override fun containerVersions(packageName: String) = emptyList<ContainerVersion>()
    override fun commitMessage(repo: String, sha: String): String? = null
}

class FakeArgo : ArgoCdGateway {
    /** refresh 가 이 횟수만큼 불린 뒤에야 새 revision 을 본다. */
    var refreshesUntilSeen = 0
    var refreshes = 0
    var seenRevision: String? = "old"
    val syncs = mutableListOf<Triple<String, String, List<ArgoResource>>>()
    /** sync 뒤 state() 가 차례로 돌려줄 작업 상태. 비면 마지막 것을 되풀이. */
    val phases = ArrayDeque<ArgoOperationState>()
    var before: ArgoOperationState? = ArgoOperationState("Succeeded", "previous op", Instant.parse("2026-10-06T11:00:00Z"), "older")
    private var last: ArgoOperationState? = null

    override fun refresh(application: String): ArgoApplicationState {
        refreshes++
        if (refreshes > refreshesUntilSeen) seenRevision = pendingRevision ?: seenRevision
        return ArgoApplicationState(seenRevision, before)
    }
    var pendingRevision: String? = null

    override fun state(application: String): ArgoApplicationState {
        if (syncs.isEmpty()) return ArgoApplicationState(seenRevision, before)
        val next = phases.pollFirst() ?: last ?: before
        last = next
        return ArgoApplicationState(seenRevision, next)
    }

    override fun sync(application: String, revision: String, resources: List<ArgoResource>) {
        syncs += Triple(application, revision, resources)
    }
}

class FakeKubernetes : KubernetesGateway {
    val snapshots = ArrayDeque<DeploymentSnapshot>()
    var pods: List<PodSnapshot> = emptyList()
    private var last: DeploymentSnapshot? = null

    override fun deployment(name: String): DeploymentSnapshot? {
        val next = snapshots.pollFirst() ?: last
        last = next
        return next
    }
    override fun pods(deploymentName: String) = pods
}

class DeploymentExecutorTest {

    private val kustomization = """
        |images:
        |  - name: ghcr.io/tear94fall/modu-chat/point-service
        |    newTag: develop-35db83f
        |  - name: ghcr.io/tear94fall/modu-commerce/web
        |    newTag: develop-23fc7a4  # 주석
        |""".trimMargin()

    private val clock = MutableClock()
    private val store = InMemoryDeploymentStore()
    private val github = FakeGitHub(kustomization)
    private val argo = FakeArgo()
    private val k8s = FakeKubernetes()
    private val timeouts = Timeouts(
        argoSync = Duration.ofMinutes(10), rollout = Duration.ofMinutes(10), argoRevisionWait = Duration.ofSeconds(60),
        poll = Duration.ofSeconds(3), podPoll = Duration.ofSeconds(2),
    )
    private val executor = DeploymentExecutor(props, github, argo, k8s, store, clock, { clock.advance(it) }, timeouts)

    private fun snapshot(generation: Long = 2, observed: Long = 2, desired: Int = 1, updated: Int = 1, ready: Int = 1, available: Int = 1, replicas: Int = updated) =
        DeploymentSnapshot("point-service", generation, observed, desired, replicas, updated, ready, available, "develop-5708871", "True", "True")

    private fun start(tag: String = "develop-5708871"): DeploymentRecord {
        val record = DeploymentRecord(id = "dep-1", service = "point-service", tag = tag, by = "joonsub2990@gmail.com", startedAt = clock.instant())
        store.add(record)
        return record
    }

    private fun succeedArgoAfter(polls: Int) {
        argo.pendingRevision = "c0ffee1234567890"
        repeat(polls) { argo.phases += ArgoOperationState("Running", null, clock.instant(), "c0ffee1234567890") }
        argo.phases += ArgoOperationState("Succeeded", "ok", clock.instant(), "c0ffee1234567890")
    }

    @Test
    fun `happy path commits, syncs only the deployment, waits for rollout and reaches 100`() {
        start()
        argo.refreshesUntilSeen = 2
        succeedArgoAfter(2)
        // 새 세대 반영 전 → 갱신됐지만 준비 안 됨 → 준비 완료
        k8s.snapshots += snapshot(generation = 3, observed = 2, updated = 0, ready = 1, available = 1, replicas = 1)
        k8s.snapshots += snapshot(generation = 3, observed = 3, desired = 2, updated = 2, ready = 1, available = 1, replicas = 2)
        k8s.snapshots += snapshot(generation = 3, observed = 3, desired = 2, updated = 2, ready = 2, available = 2, replicas = 2)
        k8s.pods = listOf(PodSnapshot("point-service-new-1", "Running", true, "", "", true))

        executor.run("dep-1")

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.SUCCEEDED, r.status)
        assertEquals(Step.DONE, r.step)
        assertEquals(100, r.percent)
        assertEquals("develop-35db83f", r.previousTag)
        assertEquals("c0ffee1234567890", r.commit?.sha)
        assertEquals("https://github.com/tear94fall/modu_infra/commit/c0ffee1234567890", r.commit?.url)
        assertNotNull(r.finishedAt)
        assertNull(r.error)
        assertEquals(listOf(StepStatus.SUCCEEDED, StepStatus.SUCCEEDED, StepStatus.SUCCEEDED), r.steps.map { it.status })
        assertEquals("커밋 c0ffee1", r.steps[0].message)
        assertEquals("동기화 완료", r.steps[1].message)
        assertEquals("준비 2/2", r.steps[2].message)
        r.steps.forEach { assertNotNull(it.startedAt); assertNotNull(it.finishedAt) }
        assertEquals(RolloutView(2, 2, 2, 2, listOf(PodView("point-service-new-1", "Running", true, ""))), r.rollout)

        // 커밋: newTag 한 줄만 바뀌고 주석은 그대로, 메시지 규칙
        val (content, sha, message) = github.puts.single()
        assertEquals("blob-1", sha)
        assertEquals("deploy: point-service → develop-5708871 (by joonsub2990@gmail.com)", message)
        assertEquals(kustomization.replace("develop-35db83f", "develop-5708871"), content)
        assertTrue(content.contains("develop-23fc7a4  # 주석"))

        // Argo: 새 revision 을 볼 때까지 refresh 를 되풀이한 뒤 Deployment 하나만 sync
        assertTrue(argo.refreshes >= 3, "refreshes=${argo.refreshes}")
        val (app, revision, resources) = argo.syncs.single()
        assertEquals("modu-dev", app)
        assertEquals("c0ffee1234567890", revision)
        assertEquals(listOf(ArgoResource("apps", "Deployment", "point-service", "modu")), resources)
    }

    @Test
    fun `rollout percent follows ready over desired`() {
        start()
        succeedArgoAfter(0)
        val seen = mutableListOf<Int>()
        k8s.snapshots += snapshot(desired = 4, updated = 4, ready = 1, available = 1, replicas = 4)
        k8s.snapshots += snapshot(desired = 4, updated = 4, ready = 3, available = 3, replicas = 4)
        k8s.snapshots += snapshot(desired = 4, updated = 4, ready = 4, available = 4, replicas = 4)
        val spy = DeploymentExecutor(props, github, argo, k8s, store, clock, { seen += store.get("dep-1")!!.percent; clock.advance(it) }, timeouts)

        spy.run("dep-1")

        // 잠들기 직전 진행률: 롤아웃 중 62(1/4), 87(3/4). 그 앞의 값은 커밋·동기화 단계.
        assertTrue(seen.containsAll(listOf(62, 87)), seen.toString())
        assertTrue(seen.zipWithNext().all { (a, b) -> a <= b }, "monotonic: $seen")
        assertEquals(100, store.get("dep-1")!!.percent)
    }

    @Test
    fun `same tag skips the commit but still syncs the branch head`() {
        start(tag = "develop-35db83f")
        argo.pendingRevision = "head000000000000"
        argo.phases += ArgoOperationState("Succeeded", null, clock.instant(), "head000000000000")
        k8s.snapshots += snapshot()

        executor.run("dep-1")

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.SUCCEEDED, r.status)
        assertTrue(github.puts.isEmpty())
        assertNull(r.commit)
        assertEquals("이미 같은 태그", r.steps[0].message)
        assertEquals(StepStatus.SUCCEEDED, r.steps[0].status)
        assertEquals("head000000000000", argo.syncs.single().second)
    }

    @Test
    fun `argo failure marks SYNC failed with the operation message`() {
        start()
        argo.pendingRevision = "c0ffee1234567890"
        argo.phases += ArgoOperationState("Running", null, clock.instant(), "c0ffee1234567890")
        argo.phases += ArgoOperationState("Failed", "one or more objects failed to apply", clock.instant(), "c0ffee1234567890")

        executor.run("dep-1")

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertEquals(Step.SYNC, r.step)
        assertEquals("Argo CD 동기화 Failed: one or more objects failed to apply", r.error)
        assertEquals(StepStatus.SUCCEEDED, r.steps[0].status)
        assertEquals(StepStatus.FAILED, r.steps[1].status)
        assertEquals(StepStatus.PENDING, r.steps[2].status)
        assertNotNull(r.finishedAt)
        assertEquals(20, r.percent.coerceAtMost(35).let { 20 }) // 커밋까지의 진행률 구간에 머문다
        assertTrue(r.percent in 20..35, "percent=${r.percent}")
    }

    @Test
    fun `a previous succeeded operation is not mistaken for ours`() {
        start()
        argo.pendingRevision = "c0ffee1234567890"
        // 우리 요청 전에 끝난 옛 작업이 먼저 보이고(다른 revision, 과거 시각), 그 다음 우리 작업이 Running → Succeeded
        argo.phases += ArgoOperationState("Succeeded", "old", Instant.parse("2026-10-06T11:00:00Z"), "older")
        argo.phases += ArgoOperationState("Running", null, clock.instant(), "c0ffee1234567890")
        argo.phases += ArgoOperationState("Succeeded", null, clock.instant(), "c0ffee1234567890")
        k8s.snapshots += snapshot()

        executor.run("dep-1")

        assertEquals(DeploymentStatus.SUCCEEDED, store.get("dep-1")!!.status)
    }

    @Test
    fun `argo sync timeout fails SYNC`() {
        start()
        argo.pendingRevision = "c0ffee1234567890"
        argo.phases += ArgoOperationState("Running", null, clock.instant(), "c0ffee1234567890")

        executor.run("dep-1")

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertEquals(Step.SYNC, r.step)
        assertEquals("Argo CD 동기화가 10분 안에 끝나지 않았습니다.", r.error)
    }

    @Test
    fun `crash looping pod of the new replica set fails ROLLOUT with the reason`() {
        start()
        succeedArgoAfter(0)
        k8s.snapshots += snapshot(generation = 3, observed = 3, desired = 1, updated = 1, ready = 0, available = 0)
        k8s.pods = listOf(
            PodSnapshot("point-service-old-1", "Running", true, "", "", false),
            PodSnapshot("point-service-new-1", "Running", false, "CrashLoopBackOff", "back-off 5m0s restarting failed container", true),
        )

        executor.run("dep-1")

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertEquals(Step.ROLLOUT, r.step)
        assertEquals("파드 point-service-new-1 CrashLoopBackOff: back-off 5m0s restarting failed container", r.error)
        assertEquals(StepStatus.FAILED, r.steps[2].status)
        assertEquals(2, r.rollout?.pods?.size)
        assertEquals("CrashLoopBackOff", r.rollout?.pods?.get(1)?.reason)
    }

    @Test
    fun `old replica set pod failures are ignored and rollout times out`() {
        start()
        succeedArgoAfter(0)
        k8s.snapshots += snapshot(generation = 3, observed = 3, desired = 1, updated = 1, ready = 0, available = 0)
        k8s.pods = listOf(PodSnapshot("point-service-old-1", "Running", false, "ImagePullBackOff", "", false))

        executor.run("dep-1")

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertEquals(Step.ROLLOUT, r.step)
        assertEquals("롤아웃이 10분 안에 끝나지 않았습니다.", r.error)
        assertEquals(50, r.percent)
    }

    @Test
    fun `github commit failure fails COMMIT and records previousTag`() {
        start()
        github.failPut = "409 Conflict"

        executor.run("dep-1")

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertEquals(Step.COMMIT, r.step)
        assertEquals("커밋 실패: 409 Conflict", r.error)
        assertEquals("develop-35db83f", r.previousTag)
        assertTrue(argo.syncs.isEmpty())
        assertEquals(StepStatus.FAILED, r.steps[0].status)
    }

    @Test
    fun `missing deployment fails ROLLOUT`() {
        start()
        succeedArgoAfter(0)
        // k8s.snapshots 비어 있음 → null

        executor.run("dep-1")

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertEquals("Deployment point-service 가 네임스페이스 modu 에 없습니다.", r.error)
    }

    @Test
    fun `a store write failure during rollout marks the deployment FAILED instead of killing the run`() {
        start()
        succeedArgoAfter(0)
        k8s.snapshots += snapshot(desired = 2, updated = 2, ready = 1, available = 1, replicas = 2)
        k8s.snapshots += snapshot(desired = 2, updated = 2, ready = 2, available = 2, replicas = 2)
        // 롤아웃 스냅샷을 쓰는 첫 UPDATE 만 실패하는 저장소(DB 순단). 그 뒤(FAILED 기록)는 다시 된다.
        var failures = 0
        val flaky = object : DeploymentStore by store {
            override fun update(id: String, update: (DeploymentRecord) -> DeploymentRecord): DeploymentRecord? {
                val current = store.get(id) ?: return null
                if (update(current).rollout != null && current.rollout == null && failures++ == 0) throw IllegalStateException("Connection refused")
                return store.update(id, update)
            }
        }
        val executor = DeploymentExecutor(props, github, argo, k8s, flaky, clock, { clock.advance(it) }, timeouts)

        executor.run("dep-1")

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertEquals(Step.ROLLOUT, r.step)
        assertEquals("배포 기록 저장 실패(DB): Connection refused", r.error)
        assertEquals(StepStatus.FAILED, r.steps[2].status)
        assertNotNull(r.finishedAt)
        assertEquals(1, failures)
    }

    @Test
    fun `a dead store does not throw out of run`() {
        start()
        val dead = object : DeploymentStore by store {
            override fun update(id: String, update: (DeploymentRecord) -> DeploymentRecord): DeploymentRecord? = throw IllegalStateException("db down")
        }
        val executor = DeploymentExecutor(props, github, argo, k8s, dead, clock, { clock.advance(it) }, timeouts)

        executor.run("dep-1") // 던지지 않는다(로그만). 기록은 RUNNING 으로 남고 재시작 복구가 닫는다.

        assertEquals(DeploymentStatus.RUNNING, store.get("dep-1")!!.status)
        assertTrue(github.puts.isEmpty())
    }

    @Test
    fun `runner refuses a second deployment of the same service while one runs`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val blockingExecutor = DeploymentExecutor(props, github, object : ArgoCdGateway by argo {
            override fun refresh(application: String): ArgoApplicationState { gate.await(); return argo.refresh(application) }
        }, k8s, store, clock, { clock.advance(it) }, timeouts)
        val runner = DeploymentRunner(store, blockingExecutor)
        argo.pendingRevision = "c0ffee1234567890"
        argo.phases += ArgoOperationState("Succeeded", null, clock.instant(), "c0ffee1234567890")
        k8s.snapshots += snapshot()
        val first = DeploymentRecord(id = "dep-1", service = "point-service", tag = "develop-5708871", by = "me", startedAt = clock.instant())

        runner.start(first)
        assertTrue(runner.isRunning("point-service"))
        val e = org.junit.jupiter.api.assertThrows<com.example.deployservice.api.ApiException> {
            runner.start(first.copy(id = "dep-2"))
        }
        assertEquals("deploy_in_progress", e.error)
        assertEquals(409, e.status.value())
        // 다른 서비스는 막지 않는다(알 수 없는 서비스라 바로 실패로 끝난다)
        runner.start(first.copy(id = "dep-3", service = "gateway-service"))

        gate.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (runner.isRunning("point-service") && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(DeploymentStatus.SUCCEEDED, store.get("dep-1")!!.status)
        assertEquals(DeploymentStatus.FAILED, store.get("dep-3")!!.status)
        runner.start(first.copy(id = "dep-4")) // 끝난 뒤엔 다시 받는다
        runner.shutdown()
    }
}
