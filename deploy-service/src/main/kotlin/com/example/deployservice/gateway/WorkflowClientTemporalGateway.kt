package com.example.deployservice.gateway

import com.example.deployservice.config.DeployProperties
import com.example.deployservice.deploy.TemporalGateway
import com.example.deployservice.deploy.workflow.DeployInput
import com.example.deployservice.deploy.workflow.DeployWorkflow
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import java.time.Duration
import java.util.Optional

/** Temporal Java SDK 의 WorkflowClient(스타터가 만든 빈)로 배포 워크플로를 시작·조회한다. */
class WorkflowClientTemporalGateway(private val client: WorkflowClient, private val properties: DeployProperties) : TemporalGateway {

    companion object {
        /** 세 단계 합 최대 ~21분(커밋 재시도 + Argo 10분 + 롤아웃 10분)의 안전망. 넘으면 서버가 실행을 끝낸다 → 기록은 다음 배포 때 Starter 가 닫는다. */
        val EXECUTION_TIMEOUT: Duration = Duration.ofMinutes(30)
    }

    override fun start(workflowId: String, input: DeployInput): String {
        val stub = client.newWorkflowStub(
            DeployWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(properties.temporal.taskQueue)
                .setWorkflowId(workflowId)
                // 끝난 실행의 ID 는 다시 쓴다(서비스마다 ID 가 하나). 실행 중이면 기본 충돌 정책이 거절한다(WorkflowExecutionAlreadyStarted).
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .setWorkflowExecutionTimeout(EXECUTION_TIMEOUT)
                .build(),
        )
        return WorkflowClient.start(stub::run, input).runId
    }

    /** NOT_FOUND 만 null. 그 외 gRPC 오류는 SDK 가 WorkflowServiceException 으로 감싸 올린다(StatusRuntimeException 이 밖으로 나오지 않는다). */
    override fun status(workflowId: String, runId: String?): WorkflowExecutionStatus? = try {
        client.newUntypedWorkflowStub(workflowId, Optional.ofNullable(runId), Optional.empty()).describe().status
    } catch (e: WorkflowNotFoundException) {
        null
    }
}
