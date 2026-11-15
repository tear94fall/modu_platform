package com.example.deployservice.deploy

import com.example.deployservice.api.ApiException
import com.example.deployservice.deploy.DeployProps.IMAGE
import com.example.deployservice.deploy.DeployProps.props
import com.example.deployservice.deploy.workflow.DeployInput
import com.example.deployservice.deploy.workflow.DeployWorkflow
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowServiceException
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * 배포 시작의 수문장 둘(DB 유니크 키 → 워크플로 ID)과 고아 RUNNING 기록 정리. Temporal 은 [TemporalGateway] 가짜로 바꾼다.
 */
class DeploymentStarterTest {

    private val store = InMemoryDeploymentStore()
    private val temporal: TemporalGateway = mock()
    private val clock = MutableClock(Instant.parse("2026-11-14T12:10:00Z"))
    private val starter = DeploymentStarter(temporal, store, clock)

    /** 기본은 10분 전에 시작한 "오래된" 기록. [age] 로 어린 기록을 만든다. */
    private fun record(id: String, service: String = "point-service", status: DeploymentStatus = DeploymentStatus.RUNNING, age: Duration = Duration.ofMinutes(10), runId: String? = null) =
        DeploymentRecord(id = id, service = service, tag = "develop-5708871", by = "me", startedAt = clock.instant().minus(age), status = status, workflowRunId = runId)

    @Test
    fun `a started workflow's run id is stored`() {
        whenever(temporal.start(eq("deploy-point-service"), any(), any())).thenReturn("run-1")

        val started = starter.start(record("dep-1"), IMAGE)

        assertEquals("run-1", started.workflowRunId)
        assertEquals("run-1", store.get("dep-1")?.workflowRunId)
        assertEquals(DeploymentStatus.RUNNING, store.get("dep-1")?.status)
        verify(temporal).start("deploy-point-service", DeployInput("dep-1", "point-service", IMAGE, "develop-5708871", "me", DeployMode.DIRECT), DeploymentStarter.DIRECT_TIMEOUT)
    }

    @Test
    fun `a canary deployment is started with CANARY mode and the eight day execution timeout`() {
        whenever(temporal.start(eq("deploy-point-service"), any(), any())).thenReturn("run-c")

        starter.start(record("dep-c").copy(mode = DeployMode.CANARY), IMAGE)

        verify(temporal).start("deploy-point-service", DeployInput("dep-c", "point-service", IMAGE, "develop-5708871", "me", DeployMode.CANARY), DeploymentStarter.CANARY_TIMEOUT)
        assertEquals(Duration.ofDays(8), DeploymentStarter.CANARY_TIMEOUT)
        assertEquals(Duration.ofMinutes(30), DeploymentStarter.DIRECT_TIMEOUT)
    }

    @Test
    fun `a running record rejects a second start with 409 when its workflow is running`() {
        store.add(record("dep-1", runId = "run-1"))
        whenever(temporal.status("deploy-point-service", null)).thenReturn(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING)

        val e = assertThrows<ApiException> { starter.start(record("dep-2"), IMAGE) }

        assertEquals("deploy_in_progress", e.error)
        assertEquals(409, e.status.value())
        assertEquals(DeploymentStatus.RUNNING, store.get("dep-1")?.status)
        assertNull(store.get("dep-2"))
        verify(temporal, never()).start(any(), any(), any())
    }

    @Test
    fun `an orphan record whose workflow is gone is closed and the new deployment starts`() {
        store.add(record("dep-1")) // 스레드 시절 기록: runId 없음, 10분 전 시작
        whenever(temporal.status("deploy-point-service", null)).thenReturn(null) // 실행이 없다
        whenever(temporal.start(eq("deploy-point-service"), any(), any())).thenReturn("run-2")

        val started = starter.start(record("dep-2"), IMAGE)

        val orphan = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, orphan.status)
        assertTrue(orphan.error!!.contains("워크플로가 없어 닫음"), orphan.error)
        assertEquals("run-2", started.workflowRunId)
        assertEquals(DeploymentStatus.RUNNING, store.get("dep-2")?.status)
    }

    @Test
    fun `an orphan record whose workflow has finished is also closed`() {
        store.add(record("dep-1", runId = "run-1"))
        whenever(temporal.status("deploy-point-service", null)).thenReturn(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT)
        whenever(temporal.status("deploy-point-service", "run-1")).thenReturn(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT)
        whenever(temporal.start(eq("deploy-point-service"), any(), any())).thenReturn("run-2")

        starter.start(record("dep-2"), IMAGE)

        assertEquals(DeploymentStatus.FAILED, store.get("dep-1")?.status)
        assertEquals(DeploymentStatus.RUNNING, store.get("dep-2")?.status)
    }

    @Test
    fun `already started workflow closes the record and is a 409`() {
        whenever(temporal.start(eq("deploy-point-service"), any(), any())).thenThrow(WorkflowExecutionAlreadyStarted(WorkflowExecution.getDefaultInstance(), "DeployWorkflow", null))

        val e = assertThrows<ApiException> { starter.start(record("dep-1"), IMAGE) }

        assertEquals("deploy_in_progress", e.error)
        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertTrue(r.error!!.contains("이미 실행 중"), r.error)
    }

    @Test
    fun `temporal unavailable closes the record and is a 503`() {
        whenever(temporal.start(eq("deploy-point-service"), any(), any())).thenThrow(StatusRuntimeException(Status.UNAVAILABLE))

        val e = assertThrows<ApiException> { starter.start(record("dep-1"), IMAGE) }

        assertEquals("temporal_unavailable", e.error)
        assertEquals(503, e.status.value())
        val r = store.get("dep-1")!!
        assertEquals(DeploymentStatus.FAILED, r.status)
        assertTrue(r.error!!.startsWith("Temporal 에 배포를 등록하지 못했습니다"), r.error)
    }

    @Test
    fun `temporal unavailable while checking an orphan is a 503 and leaves the record alone`() {
        store.add(record("dep-1"))
        // 실제 SDK 는 gRPC 오류를 WorkflowServiceException 으로 감싼다(StatusRuntimeException 이 밖으로 나오지 않는다 — 리뷰에서 확인)
        whenever(temporal.status("deploy-point-service", null)).thenThrow(WorkflowServiceException(WorkflowExecution.getDefaultInstance(), "DeployWorkflow", StatusRuntimeException(Status.UNAVAILABLE)))

        val e = assertThrows<ApiException> { starter.start(record("dep-2"), IMAGE) }

        assertEquals("temporal_unavailable", e.error)
        assertEquals(503, e.status.value())
        assertEquals(DeploymentStatus.RUNNING, store.get("dep-1")?.status)
        assertNull(store.get("dep-2"))
    }

    /**
     * 동시 시작 레이스: A 가 행을 넣고 temporal.start 가 돌아오기 전(수십 ms)에 B 가 들어오면 Temporal 에는 아직 A 의 실행이 없다.
     * 그 어린 행을 고아로 닫으면 살아 있는 배포가 FAILED 로 보인다 — runId 가 없고 어린 행은 "시작 진행 중" 으로 보고 409.
     */
    @Test
    fun `a young RUNNING record without a run id is treated as in progress even if no workflow is found`() {
        store.add(record("dep-a", age = Duration.ofSeconds(10)))
        whenever(temporal.status("deploy-point-service", null)).thenReturn(null)

        val e = assertThrows<ApiException> { starter.start(record("dep-b"), IMAGE) }

        assertEquals("deploy_in_progress", e.error)
        assertEquals(DeploymentStatus.RUNNING, store.get("dep-a")?.status)
        assertNull(store.get("dep-b"))
        verify(temporal, never()).start(any(), any(), any())
    }

    @Test
    fun `a RUNNING record with a run id is kept while that very run is still running`() {
        store.add(record("dep-1", runId = "run-1"))
        // 최신 실행은 끝났다고 보여도(다른 run) 기록이 가리키는 run 이 살아 있으면 건드리지 않는다
        whenever(temporal.status("deploy-point-service", null)).thenReturn(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED)
        whenever(temporal.status("deploy-point-service", "run-1")).thenReturn(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING)

        val e = assertThrows<ApiException> { starter.start(record("dep-2"), IMAGE) }

        assertEquals("deploy_in_progress", e.error)
        assertEquals(DeploymentStatus.RUNNING, store.get("dep-1")?.status)
    }
}
