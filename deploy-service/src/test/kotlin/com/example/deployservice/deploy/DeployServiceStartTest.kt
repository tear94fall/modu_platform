package com.example.deployservice.deploy

import com.example.deployservice.deploy.DeployProps.props
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
    private val service = DefaultDeployService(props, github, registry, kubernetes, store, DeploymentStarter(temporal, store, clock), clock)

    @Test
    fun `deploy closes an orphan RUNNING record whose workflow is gone and starts the new one`() {
        store.add(DeploymentRecord(id = "dep-orphan", service = "point-service", tag = "develop-35db83f", by = "me", startedAt = clock.instant().minusSeconds(600)))
        whenever(temporal.status("deploy-point-service", null)).thenReturn(null)
        whenever(temporal.start(eq("deploy-point-service"), any())).thenReturn("run-9")

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
}
