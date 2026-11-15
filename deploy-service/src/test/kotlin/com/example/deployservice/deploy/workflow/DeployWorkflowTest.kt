package com.example.deployservice.deploy.workflow

import com.example.deployservice.config.TemporalConverters
import com.example.deployservice.deploy.DeployProps.IMAGE
import com.example.deployservice.deploy.DeployProps.props
import com.example.deployservice.deploy.DeployMode
import com.example.deployservice.deploy.DeploymentRecord
import com.example.deployservice.deploy.DeploymentStatus
import com.example.deployservice.deploy.FakeArgo
import com.example.deployservice.deploy.FakeGitHub
import com.example.deployservice.deploy.FakeKubernetes
import com.example.deployservice.deploy.InMemoryDeploymentStore
import com.example.deployservice.deploy.MutableClock
import com.example.deployservice.deploy.PodView
import com.example.deployservice.deploy.RolloutView
import com.example.deployservice.deploy.Step
import com.example.deployservice.deploy.StepStatus
import com.example.deployservice.gateway.ArgoOperationState
import com.example.deployservice.gateway.ArgoResource
import com.example.deployservice.gateway.DeploymentSnapshot
import com.example.deployservice.gateway.PodSnapshot
import com.example.deployservice.gateway.RolloutSnapshot
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowStub
import java.time.Duration
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowFailedException
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 워크플로 전체(COMMIT → SYNC → ROLLOUT)를 인메모리 Temporal(TestWorkflowEnvironment, 시간 건너뛰기)에서 돌린다. 액티비티는 진짜 구현 + 가짜 게이트웨이.
 * 시나리오는 예전 DeploymentExecutorTest 와 같다 — 기록(InMemoryDeploymentStore)에 남는 모양이 진실이다.
 */
class DeployWorkflowTest {

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

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var workflow: DeployWorkflow

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(TemporalConverters.kotlinDataConverter()).build())
                .build(),
        )
        val worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(DeployWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(
            CommitActivitiesImpl(props, github),
            ArgoActivitiesImpl(props, argo),
            KubernetesActivitiesImpl(props, k8s),
            RecordActivitiesImpl(store, clock),
        )
        env.start()
        workflow = env.workflowClient.newWorkflowStub(
            DeployWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId("deploy-point-service").build(),
        )
    }

    @AfterEach
    fun tearDown() = env.close()

    /** Rollout 스냅샷(v2 경로). 단계 없는 Rollout = 지금의 롤링. */
    private fun rollout(desired: Int = 1, updated: Int = 1, ready: Int = 1, available: Int = 1, phase: String = "Healthy", stable: String = "new", current: String = "new",
                        weights: List<Int> = emptyList(), stepIndex: Int? = null, paused: Boolean = false, aborted: Boolean = false) =
        RolloutSnapshot("point-service", desired, updated, updated, ready, available, phase, null, stepIndex, weights, weights.size * 2, paused, aborted, current, stable, "develop-5708871")

    private fun start(tag: String = "develop-5708871", mode: DeployMode = DeployMode.DIRECT): DeployInput {
        store.add(DeploymentRecord(id = "dep-1", service = "point-service", tag = tag, by = "joonsub2990@gmail.com", startedAt = clock.instant(), mode = mode))
        return DeployInput("dep-1", "point-service", IMAGE, tag, "joonsub2990@gmail.com", mode)
    }

    /** 비동기로 시작해 Signal 을 보낼 수 있게. 끝나면 결과를 기다린다. */
    private fun startAsync(input: DeployInput): WorkflowStub {
        WorkflowClient.start(workflow::run, input)
        return WorkflowStub.fromTyped(workflow)
    }

    private fun waitUntilWaiting() {
        for (i in 0 until 50) { if (store.get("dep-1")?.canary?.waiting == true) return; Thread.sleep(100) }
        throw AssertionError("canary never reached the waiting state: ${store.get("dep-1")?.canary}")
    }

    private fun succeedArgoAfter(polls: Int) {
        argo.pendingRevision = "c0ffee1234567890"
        repeat(polls) { argo.phases += ArgoOperationState("Running", null, clock.instant(), "c0ffee1234567890") }
        argo.phases += ArgoOperationState("Succeeded", "ok", clock.instant(), "c0ffee1234567890")
    }

    private fun readyRollout() {
        k8s.rollouts += rollout()
        k8s.rolloutPods = listOf(PodSnapshot("point-service-new-1", "Running", true, "", "", true))
    }

    private fun runExpectingFailure(input: DeployInput): WorkflowFailedException = assertThrows<WorkflowFailedException> { workflow.run(input) }

    @Test
    fun `happy path commits, syncs only the deployment, waits for rollout and reaches 100`() {
        val input = start()
        argo.refreshesUntilSeen = 2
        succeedArgoAfter(2)
        k8s.rollouts += rollout(desired = 2, updated = 0, ready = 1, available = 1, phase = "Progressing", stable = "old", current = "new")
        k8s.rollouts += rollout(desired = 2, updated = 2, ready = 1, available = 1, phase = "Progressing", stable = "old", current = "new")
        k8s.rollouts += rollout(desired = 2, updated = 2, ready = 2, available = 2)
        k8s.rolloutPods = listOf(PodSnapshot("point-service-new-1", "Running", true, "", "", true))

        workflow.run(input)

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.SUCCEEDED, r.status)
        assertEquals(listOf("point-service"), k8s.fullPromotes) // DIRECT 는 단계를 전부 건너뛴다
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

        val (content, sha, message) = github.puts.single()
        assertEquals("blob-1", sha)
        assertEquals("deploy: point-service → develop-5708871 (by joonsub2990@gmail.com)", message)
        assertEquals(kustomization.replace("develop-35db83f", "develop-5708871"), content)
        assertTrue(content.contains("develop-23fc7a4  # 주석"))

        assertTrue(argo.refreshes >= 3, "refreshes=${argo.refreshes}")
        val (app, revision, resources) = argo.syncs.single()
        assertEquals("modu-dev", app)
        assertEquals("c0ffee1234567890", revision)
        assertEquals(listOf(ArgoResource("apps", "Deployment", "point-service", "modu")), resources)
    }

    @Test
    fun `same tag skips the commit but still syncs the branch head`() {
        val input = start("develop-35db83f")
        argo.pendingRevision = "head000000000000"
        argo.phases += ArgoOperationState("Succeeded", "ok", clock.instant(), "head000000000000")
        readyRollout()

        workflow.run(input)

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.SUCCEEDED, r.status)
        assertTrue(github.puts.isEmpty())
        assertNull(r.commit)
        assertEquals("이미 같은 태그", r.steps[0].message)
        assertEquals("head000000000000", argo.syncs.single().second)
    }

    @Test
    fun `sha conflict is retried and then succeeds`() {
        val input = start()
        github.conflictsLeft = 2
        succeedArgoAfter(0)
        readyRollout()

        workflow.run(input)

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.SUCCEEDED, r.status)
        assertEquals(1, github.puts.size)
        assertEquals(3, github.reads, "충돌마다 처음부터 다시 읽는다")
        assertEquals("커밋 c0ffee1", r.steps[0].message)
    }

    @Test
    fun `five sha conflicts fail COMMIT`() {
        val input = start()
        github.conflictsLeft = 5

        runExpectingFailure(input)

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertEquals(Step.COMMIT, r.step)
        assertTrue(r.error!!.contains("sha 충돌"), r.error)
        assertEquals(StepStatus.FAILED, r.steps[0].status)
        assertTrue(github.puts.isEmpty())
        assertTrue(argo.syncs.isEmpty())
    }

    @Test
    fun `argo failure marks SYNC failed with the operation message`() {
        val input = start()
        argo.pendingRevision = "c0ffee1234567890"
        argo.phases += ArgoOperationState("Failed", "one or more objects failed to apply", clock.instant(), "c0ffee1234567890")

        runExpectingFailure(input)

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertEquals(Step.SYNC, r.step)
        assertEquals("Argo CD 동기화 Failed: one or more objects failed to apply", r.error)
        assertEquals(StepStatus.SUCCEEDED, r.steps[0].status)
        assertEquals(StepStatus.FAILED, r.steps[1].status)
        assertEquals(StepStatus.PENDING, r.steps[2].status)
        assertNotNull(r.finishedAt)
        assertTrue(r.percent in 20..35, "percent=${r.percent}")
    }

    @Test
    fun `a previous succeeded operation is not mistaken for ours`() {
        val input = start()
        argo.pendingRevision = "c0ffee1234567890"
        // 첫 두 번은 이전 작업(revision older, 11:00) 만 보인다 → 우리 것이 아니라 계속 기다린다
        argo.phases += argo.before!!
        argo.phases += argo.before!!
        argo.phases += ArgoOperationState("Succeeded", "ok", clock.instant(), "c0ffee1234567890")
        readyRollout()

        workflow.run(input)

        assertEquals(DeploymentStatus.SUCCEEDED, store.get("dep-1")!!.status)
    }

    @Test
    fun `argo sync timeout fails SYNC`() {
        val input = start()
        argo.pendingRevision = "c0ffee1234567890"
        argo.phases += ArgoOperationState("Running", null, clock.instant(), "c0ffee1234567890") // 영원히 Running

        runExpectingFailure(input)

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertEquals(Step.SYNC, r.step)
        assertEquals("Argo CD 동기화가 10분 안에 끝나지 않았습니다.", r.error)
    }

    @Test
    fun `crash looping pod of the new replica set fails ROLLOUT with the reason`() {
        val input = start()
        succeedArgoAfter(0)
        k8s.rollouts += rollout(desired = 2, updated = 2, ready = 1, available = 1, phase = "Progressing", stable = "old", current = "new")
        k8s.rolloutPods = listOf(
            PodSnapshot("point-service-old-1", "Running", true, "", "", false),
            PodSnapshot("point-service-new-1", "Running", false, "CrashLoopBackOff", "back-off 5m0s restarting failed container", true),
        )

        runExpectingFailure(input)

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
        val input = start()
        succeedArgoAfter(0)
        k8s.rollouts += rollout(desired = 1, updated = 1, ready = 0, available = 0, phase = "Progressing", stable = "old", current = "new")
        k8s.rolloutPods = listOf(PodSnapshot("point-service-old-1", "Running", false, "CrashLoopBackOff", "x", false))

        runExpectingFailure(input)

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertEquals(Step.ROLLOUT, r.step)
        assertEquals("롤아웃이 10분 안에 끝나지 않았습니다.", r.error)
    }

    @Test
    fun `rollout percent follows ready over desired`() {
        val input = start()
        succeedArgoAfter(0)
        k8s.rollouts += rollout(desired = 4, updated = 4, ready = 1, available = 1, phase = "Progressing", stable = "old", current = "new")
        k8s.rollouts += rollout(desired = 4, updated = 4, ready = 3, available = 3, phase = "Progressing", stable = "old", current = "new")
        k8s.rollouts += rollout(desired = 4, updated = 4, ready = 4, available = 4)
        k8s.rolloutPods = listOf(PodSnapshot("point-service-new-1", "Running", true, "", "", true))

        workflow.run(input)

        assertEquals(100, store.get("dep-1")!!.percent)
        assertEquals("준비 4/4", store.get("dep-1")!!.steps[2].message)
    }

    // ---- 카나리(CANARY) ----------------------------------------------------------------------------------------------------

    /** 파드 2개: 50% 에서 멈춤(Paused, step index 1) → promote Signal → Healthy. */
    private fun pausedAt50() = rollout(desired = 2, updated = 1, ready = 2, available = 2, phase = "Paused", stable = "old", current = "new", weights = listOf(50), stepIndex = 1, paused = true)

    @Test
    fun `canary waits at the first step until a promote signal, then finishes with history`() {
        val input = start(mode = DeployMode.CANARY)
        succeedArgoAfter(0)
        k8s.rollouts += pausedAt50()
        k8s.rolloutPods = listOf(PodSnapshot("point-service-new-1", "Running", true, "", "", true), PodSnapshot("point-service-old-1", "Running", true, "", "", false))
        val stub = startAsync(input)
        waitUntilWaiting()

        val waiting = store.get("dep-1")!!.canary!!
        assertEquals(50, waiting.weight); assertEquals(1, waiting.currentStep); assertEquals(1, waiting.totalSteps)
        assertEquals(listOf("point-service-new-1"), waiting.canaryPods); assertEquals(listOf("point-service-old-1"), waiting.stablePods)
        assertNotNull(waiting.waitingSince)
        assertTrue(k8s.fullPromotes.isEmpty())

        k8s.rollouts += rollout(desired = 2, updated = 2, ready = 2, available = 2, weights = listOf(50), stepIndex = 2)
        workflow.promote("joonsub2990@gmail.com")
        stub.getResult(Void::class.java)

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.SUCCEEDED, r.status)
        assertEquals(listOf("point-service"), k8s.promotes)
        assertEquals(1, r.canary!!.history.size)
        assertEquals("PROMOTE", r.canary!!.history[0].action); assertEquals(50, r.canary!!.history[0].weight); assertEquals("joonsub2990@gmail.com", r.canary!!.history[0].by)
        assertEquals(false, r.canary!!.waiting)
        assertEquals("준비 2/2", r.steps[2].message)
    }

    @Test
    fun `canary abort restores stable, reverts the tag in git and records ABORTED`() {
        val input = start(mode = DeployMode.CANARY)
        succeedArgoAfter(0)
        k8s.rollouts += pausedAt50()
        k8s.rolloutPods = listOf(PodSnapshot("point-service-new-1", "Running", true, "", "", true), PodSnapshot("point-service-old-1", "Running", true, "", "", false))
        val stub = startAsync(input)
        waitUntilWaiting()

        // abort 뒤: stable 로 복귀(aborted, ready == desired) → 이전 태그 커밋 → Sync → Healthy(템플릿이 옛 태그로 돌아옴)
        k8s.rollouts += rollout(desired = 2, updated = 0, ready = 2, available = 2, phase = "Degraded", stable = "old", current = "new", weights = listOf(50), stepIndex = 1, aborted = true)
        k8s.rollouts += rollout(desired = 2, updated = 2, ready = 2, available = 2, stable = "old", current = "old", weights = listOf(50))
        argo.pendingRevision = "c0ffee1234567890"
        argo.phases += ArgoOperationState("Succeeded", "ok", clock.instant(), "c0ffee1234567890")
        workflow.abort("joonsub2990@gmail.com")
        stub.getResult(Void::class.java)

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.ABORTED, r.status)
        assertEquals(listOf("point-service"), k8s.aborts)
        assertEquals(2, github.puts.size, "새 태그 커밋 + 이전 태그 원복 커밋")
        assertTrue(github.puts[1].first.contains("newTag: develop-35db83f"), "git 은 이전 태그로")
        assertEquals("ABORT", r.canary!!.history.single().action)
        assertNotNull(r.finishedAt)
        assertEquals(2, argo.syncs.size, "원복도 Sync 한다")
    }

    @Test
    fun `canary waiting longer than seven days is aborted by the system`() {
        val input = start(mode = DeployMode.CANARY)
        succeedArgoAfter(0)
        k8s.rollouts += pausedAt50()
        k8s.rolloutPods = listOf(PodSnapshot("point-service-new-1", "Running", true, "", "", true))
        val stub = startAsync(input)
        waitUntilWaiting()
        // 아무도 누르지 않는다. 시간 건너뛰기: await(1h) × 168 → 시스템 ABORT. abort 뒤 복귀·원복·Sync 스냅샷
        argo.pendingRevision = "c0ffee1234567890"
        argo.phases += ArgoOperationState("Succeeded", "ok", clock.instant(), "c0ffee1234567890")
        env.sleep(Duration.ofDays(7).plusMinutes(5))
        k8s.rollouts += rollout(desired = 2, updated = 0, ready = 2, available = 2, phase = "Degraded", stable = "old", current = "new", weights = listOf(50), stepIndex = 1, aborted = true)
        k8s.rollouts += rollout(desired = 2, updated = 2, ready = 2, available = 2, stable = "old", current = "old", weights = listOf(50))
        stub.getResult(Void::class.java)

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.ABORTED, r.status)
        assertEquals("system", r.canary!!.history.single().by)
        assertTrue(r.error!!.contains("7일"), r.error)
    }

    @Test
    fun `a crash looping canary pod fails the deployment during the waiting heartbeat`() {
        val input = start(mode = DeployMode.CANARY)
        succeedArgoAfter(0)
        k8s.rollouts += pausedAt50()
        k8s.rolloutPods = listOf(PodSnapshot("point-service-new-1", "Running", true, "", "", true))
        val stub = startAsync(input)
        waitUntilWaiting()
        k8s.rolloutPods = listOf(PodSnapshot("point-service-new-1", "Running", false, "CrashLoopBackOff", "boom", true))
        env.sleep(Duration.ofMinutes(61))

        assertThrows<WorkflowFailedException> { stub.getResult(Void::class.java) }
        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertEquals("파드 point-service-new-1 CrashLoopBackOff: boom", r.error)
    }

    companion object {
        const val TASK_QUEUE = "deploy-test"
    }
}
