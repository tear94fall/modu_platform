package com.example.deployservice.deploy

import com.example.deployservice.deploy.workflow.DeployInput
import io.temporal.api.enums.v1.WorkflowExecutionStatus

/**
 * Temporal 중 배포 시작이 쓰는 두 동작. 구현은 [com.example.deployservice.gateway.WorkflowClientTemporalGateway], 테스트는 mock.
 * 서버 연결 실패는 SDK 예외(io.temporal.client.WorkflowServiceException 등 — gRPC 오류를 감싼다)로 그대로 올린다. Starter 가 503 으로 바꾼다.
 */
interface TemporalGateway {
    /** 워크플로 [workflowId] 를 시작하고 runId 를 돌려준다. 이미 실행 중이면 [io.temporal.client.WorkflowExecutionAlreadyStarted]. [executionTimeout] 은 서버 안전망. */
    fun start(workflowId: String, input: DeployInput, executionTimeout: java.time.Duration): String

    /** 실행 중인 워크플로에 Signal([name] = promote | abort, 인자 [by]). 실행이 없거나 끝났으면 [io.temporal.client.WorkflowNotFoundException]. */
    fun signal(workflowId: String, runId: String, name: String, by: String)

    /** [workflowId] 의 실행 상태 — [runId] 가 있으면 그 실행, 없으면 최신 실행. 실행이 없으면 null. */
    fun status(workflowId: String, runId: String?): WorkflowExecutionStatus?
}
