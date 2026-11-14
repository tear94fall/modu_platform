package com.example.deployservice.deploy.workflow

import com.example.deployservice.deploy.DeployProps.IMAGE
import com.example.deployservice.deploy.DeployProps.props
import com.example.deployservice.deploy.DeploymentRecord
import com.example.deployservice.deploy.DeploymentStatus
import com.example.deployservice.deploy.FakeArgo
import com.example.deployservice.deploy.FakeGitHub
import com.example.deployservice.deploy.FakeKubernetes
import com.example.deployservice.deploy.InMemoryDeploymentStore
import com.example.deployservice.deploy.MutableClock
import com.example.deployservice.deploy.Step
import com.example.deployservice.deploy.StepStatus
import com.example.deployservice.gateway.ArgoOperationState
import com.example.deployservice.gateway.DeploymentSnapshot
import com.example.deployservice.gateway.PodSnapshot
import io.temporal.failure.ApplicationFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** 액티비티 하나씩 — 게이트웨이를 감싸 Temporal 실패 타입(재시도 가능/불가)으로 바꾸는 부분과 기록 쓰기. 워크플로 없이 직접 부른다. */
class DeployActivitiesTest {

    private val kustomization = """
        |images:
        |  - name: ghcr.io/tear94fall/modu-chat/point-service
        |    newTag: develop-35db83f
        |""".trimMargin()

    private val github = FakeGitHub(kustomization)
    private val argo = FakeArgo()
    private val k8s = FakeKubernetes()
    private val clock = MutableClock()
    private val store = InMemoryDeploymentStore()

    private val commit = CommitActivitiesImpl(props, github)
    private val argoActs = ArgoActivitiesImpl(props, argo)
    private val k8sActs = KubernetesActivitiesImpl(props, k8s)
    private val records = RecordActivitiesImpl(store, clock)

    private fun snapshot(desired: Int = 1, updated: Int = 1, ready: Int = 1, available: Int = 1) =
        DeploymentSnapshot("point-service", 3, 3, desired, updated, updated, ready, available, "develop-5708871", "True", "True")

    @Test
    fun `commitTag replaces the tag and reports the commit`() {
        val out = commit.commitTag("point-service", IMAGE, "develop-5708871", "joonsub2990@gmail.com")

        assertEquals("c0ffee1234567890", out.revision)
        assertEquals("c0ffee1234567890", out.commitSha)
        assertEquals("develop-35db83f", out.previousTag)
        assertEquals("커밋 c0ffee1", out.message)
        val (content, sha, message) = github.puts.single()
        assertEquals("blob-1", sha)
        assertEquals("deploy: point-service → develop-5708871 (by joonsub2990@gmail.com)", message)
        assertTrue(content.contains("newTag: develop-5708871"))
    }

    @Test
    fun `commitTag with the same tag skips the put and uses branch head`() {
        val out = commit.commitTag("point-service", IMAGE, "develop-35db83f", "me")

        assertTrue(github.puts.isEmpty())
        assertEquals("head000000000000", out.revision)
        assertNull(out.commitSha)
        assertEquals("이미 같은 태그", out.message)
    }

    @Test
    fun `commitTag turns a sha conflict into a retryable sha_conflict failure`() {
        github.conflictsLeft = 1

        val e = assertThrows<ApplicationFailure> { commit.commitTag("point-service", IMAGE, "develop-5708871", "me") }

        assertEquals("sha_conflict", e.type)
        assertFalse(e.isNonRetryable)
    }

    @Test
    fun `commitTag other put failures are not retried`() {
        github.failPut = "boom"

        val e = assertThrows<ApplicationFailure> { commit.commitTag("point-service", IMAGE, "develop-5708871", "me") }

        assertTrue(e.isNonRetryable)
        assertTrue(e.originalMessage.startsWith("커밋 실패: "), e.originalMessage)
    }

    @Test
    fun `rolloutStatus reports a crash looping pod of the newest replica set`() {
        k8s.snapshots += snapshot(desired = 2, updated = 2, ready = 1, available = 1)
        k8s.pods = listOf(
            PodSnapshot("point-service-old-1", "Running", true, "", "", false),
            PodSnapshot("point-service-new-1", "Running", false, "CrashLoopBackOff", "back-off 5m0s restarting failed container", true),
        )

        val probe = k8sActs.rolloutStatus("point-service")

        assertFalse(probe.rolledOut)
        assertEquals("파드 point-service-new-1 CrashLoopBackOff: back-off 5m0s restarting failed container", probe.failingPod)
        assertEquals(2, probe.view.pods.size)
    }

    @Test
    fun `rolloutStatus ignores old replica set pods and reports rolled out`() {
        k8s.snapshots += snapshot(desired = 1, updated = 1, ready = 1, available = 1)
        k8s.pods = listOf(PodSnapshot("point-service-old-1", "Running", false, "CrashLoopBackOff", "x", false))

        val probe = k8sActs.rolloutStatus("point-service")

        assertTrue(probe.rolledOut)
        assertNull(probe.failingPod)
    }

    @Test
    fun `rolloutStatus without a deployment is a non-retryable failure`() {
        val e = assertThrows<ApplicationFailure> { k8sActs.rolloutStatus("point-service") }
        assertTrue(e.isNonRetryable)
        assertEquals("Deployment point-service 가 네임스페이스 modu 에 없습니다.", e.originalMessage)
    }

    @Test
    fun `operation ignores an older succeeded operation`() {
        argo.pendingRevision = "c0ffee1234567890"
        argo.sync("modu-dev", "c0ffee1234567890", emptyList())
        // state() 는 syncs 가 있으면 phases → 비면 before(이전 작업, revision "older", 11:00) 를 돌려준다
        val requestedAt = Instant.parse("2026-10-06T12:00:00Z").toEpochMilli()

        assertNull(argoActs.operation("c0ffee1234567890", requestedAt))
    }

    @Test
    fun `operation returns our terminal operation`() {
        argo.pendingRevision = "c0ffee1234567890"
        argo.sync("modu-dev", "c0ffee1234567890", emptyList())
        argo.phases += ArgoOperationState("Succeeded", "ok", Instant.parse("2026-10-06T12:00:01Z"), "c0ffee1234567890")

        val op = argoActs.operation("c0ffee1234567890", Instant.parse("2026-10-06T12:00:00Z").toEpochMilli())!!

        assertEquals("Succeeded", op.phase)
        assertTrue(op.terminal)
    }

    @Test
    fun `record activities write steps and close the record`() {
        store.add(DeploymentRecord(id = "dep-1", service = "point-service", tag = "develop-5708871", by = "me", startedAt = clock.instant()))

        records.startStep("dep-1", Step.COMMIT, 5)
        records.finishStep("dep-1", Step.COMMIT, 20, "커밋 c0ffee1")
        records.startStep("dep-1", Step.SYNC, 20)
        records.message("dep-1", Step.SYNC, "동기화 중 (c0ffee1)")
        records.succeed("dep-1")

        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.SUCCEEDED, r.status)
        assertEquals(Step.DONE, r.step)
        assertEquals(100, r.percent)
        assertEquals(StepStatus.SUCCEEDED, r.steps[0].status)
        assertEquals("커밋 c0ffee1", r.steps[0].message)
        assertEquals("동기화 중 (c0ffee1)", r.steps[1].message)
        assertEquals(clock.instant(), r.finishedAt)
    }

    @Test
    fun `record fail does not overwrite a closed record`() {
        store.add(DeploymentRecord(id = "dep-1", service = "point-service", tag = "t", by = "me", startedAt = clock.instant(), status = DeploymentStatus.SUCCEEDED))

        records.fail("dep-1", Step.ROLLOUT, "late failure")

        assertEquals(DeploymentStatus.SUCCEEDED, store.get("dep-1")!!.status)
        assertNull(store.get("dep-1")!!.error)
    }
}
