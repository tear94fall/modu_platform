package com.example.deployservice.deploy

import com.example.deployservice.deploy.DeployProps.props
import com.example.deployservice.gateway.ErrorLogs
import com.example.deployservice.gateway.LogsGateway
import com.example.deployservice.gateway.PodSnapshot
import com.example.deployservice.gateway.RolloutSnapshot
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.mockito.kotlin.verify
import java.time.Duration
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * DeployService.deploy 가 고아 RUNNING 기록을 Starter 의 조정에 맡기는지 — 서비스 계층에서 먼저 409 로 끊으면 고아가 영원히 남는다
 * (dev 에서 terminate 뒤 재배포가 409 로 막힌 2026-11-15 사건).
 */
class DeployServiceStartTest {

    private val kustomization = """
        |images:
        |  - name: ghcr.io/tear94fall/modu-chat/point-service
        |    newTag: develop-35db83f
        |""".trimMargin()

    private val github = FakeGitHub(kustomization)
    private val registry = FakeRegistry(listOf(ContainerVersion(listOf("develop-5708871"), Instant.EPOCH)))
    private val kubernetes = FakeKubernetes()
    private val store = InMemoryDeploymentStore()
    private val clock = MutableClock()
    private val temporal: TemporalGateway = mock()
    private val logs: LogsGateway = mock()
    private val service = DefaultDeployService(props, github, registry, kubernetes, store, DeploymentStarter(temporal, store, clock), clock, logs)

    private fun rollout(desired: Int, weights: List<Int> = emptyList(), phase: String = "Healthy", stepIndex: Int? = null, paused: Boolean = false) =
        RolloutSnapshot("point-service", desired, desired, desired, desired, desired, phase, null, stepIndex, weights, weights.size * 2, paused, false, "new", if (paused) "old" else "new", "develop-35db83f")

    private fun canaryRecord(waiting: Boolean) = DeploymentRecord(
        id = "dep-c", service = "point-service", tag = "develop-5708871", by = "me", startedAt = clock.instant(), mode = DeployMode.CANARY, workflowRunId = "run-c",
        canary = CanaryView(totalSteps = 1, currentStep = 1, weight = 50, waiting = waiting, waitingSince = clock.instant(), canaryPods = listOf("point-service-new-1"), stablePods = listOf("point-service-old-1")),
    )

    @Test
    fun `deploy closes an orphan RUNNING record whose workflow is gone and starts the new one`() {
        store.add(DeploymentRecord(id = "dep-orphan", service = "point-service", tag = "develop-35db83f", by = "me", startedAt = clock.instant().minusSeconds(600)))
        whenever(temporal.status("deploy-point-service", null)).thenReturn(null)
        whenever(temporal.start(eq("deploy-point-service"), any(), any())).thenReturn("run-9")

        val started = service.deploy("point-service", "develop-5708871", "me", "me-id")

        assertEquals(DeploymentStatus.RUNNING, started.status)
        assertEquals("run-9", store.get(started.id)?.workflowRunId)
        val orphan = store.get("dep-orphan")!!
        assertEquals(DeploymentStatus.FAILED, orphan.status)
        assertTrue(orphan.error!!.contains("워크플로가 없어 닫음"), orphan.error)
    }

    @Test
    fun `deploy is a 409 while the workflow is really running`() {
        store.add(DeploymentRecord(id = "dep-live", service = "point-service", tag = "develop-35db83f", by = "me", startedAt = clock.instant()))
        whenever(temporal.status("deploy-point-service", null)).thenReturn(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING)

        val e = org.junit.jupiter.api.assertThrows<com.example.deployservice.api.ApiException> { service.deploy("point-service", "develop-5708871", "me", null) }

        assertEquals("deploy_in_progress", e.error)
        assertEquals(DeploymentStatus.RUNNING, store.get("dep-live")?.status)
    }

    @Test
    fun `a canary deploy needs a rollout with two or more pods`() {
        kubernetes.rollouts += rollout(desired = 1)

        val e = org.junit.jupiter.api.assertThrows<com.example.deployservice.api.ApiException> { service.deploy("point-service", "develop-5708871", "me", null, DeployMode.CANARY) }

        assertEquals("canary_not_available", e.error)
        assertEquals(400, e.status.value())
        assertNull(store.list("point-service", 1).firstOrNull())
    }

    @Test
    fun `a canary deploy with two pods starts in CANARY mode`() {
        kubernetes.rollouts += rollout(desired = 2, weights = listOf(50))
        whenever(temporal.start(eq("deploy-point-service"), any(), any())).thenReturn("run-c")

        val started = service.deploy("point-service", "develop-5708871", "me", null, DeployMode.CANARY)

        assertEquals(DeployMode.CANARY, store.get(started.id)?.mode)
    }

    @Test
    fun `promote signals the waiting canary, a non waiting record is 409`() {
        store.add(canaryRecord(waiting = true))
        service.promote("dep-c", "me")
        verify(temporal).signal("deploy-point-service", "run-c", "promote", "me")

        store.update("dep-c") { it.copy(canary = it.canary!!.copy(waiting = false)) }
        val e = org.junit.jupiter.api.assertThrows<com.example.deployservice.api.ApiException> { service.abort("dep-c", "me") }
        assertEquals("not_waiting", e.error)
        assertEquals(409, e.status.value())
    }

    @Test
    fun `a waiting canary record is enriched with the live rollout state and its errors come from the log gateway`() {
        store.add(canaryRecord(waiting = true))
        kubernetes.rollouts += rollout(desired = 2, weights = listOf(50), phase = "Paused", stepIndex = 1, paused = true)
        kubernetes.rolloutPods = listOf(PodSnapshot("point-service-new-1", "Running", true, "", "", true), PodSnapshot("point-service-old-1", "Running", true, "", "", false))
        whenever(logs.errors(eq(listOf("point-service-new-1")), any(), any())).thenReturn(ErrorLogs(0, emptyList()))

        val d = service.deployment("dep-c")
        val live = d.canary!!.live
        assertNotNull(live)
        assertEquals("Paused", live!!.phase); assertEquals(50, live.weight); assertEquals(2, live.pods.size)
        assertEquals(0L, service.errors("dep-c", clock.instant().minus(Duration.ofMinutes(1))).count)
        assertNull(service.deployments("point-service", 5).deployments.first().canary!!.live, "목록은 라이브를 채우지 않는다")
    }

    @Test
    fun `services report canary capability, steps and a paused rollout without a record`() {
        kubernetes.rollouts += rollout(desired = 2, weights = listOf(50), phase = "Paused", stepIndex = 1, paused = true)

        val view = service.services().services.single { it.name == "point-service" }

        assertEquals(true, view.canaryCapable)
        assertEquals(listOf(50), view.canarySteps)
        assertEquals(ServiceHealth.PAUSED, view.status)
        assertEquals(1, view.rolloutPaused?.stepIndex); assertEquals(50, view.rolloutPaused?.weight); assertNull(view.rolloutPaused?.deploymentId)
        assertEquals(2, view.desiredReplicas)
    }

    @Test
    fun `rollout promote on a paused service without a record patches the rollout, with a running record it is 409`() {
        kubernetes.rollouts += rollout(desired = 2, weights = listOf(50), phase = "Paused", stepIndex = 1, paused = true)
        service.promoteRollout("point-service", "me")
        assertEquals(listOf("point-service"), kubernetes.promotes)

        store.add(canaryRecord(waiting = true))
        val e = org.junit.jupiter.api.assertThrows<com.example.deployservice.api.ApiException> { service.abortRollout("point-service", "me") }
        assertEquals("use_deployment", e.error)
        assertTrue(kubernetes.aborts.isEmpty())
    }
}
