package com.example.deployservice.deploy.workflow

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * 배포 워크플로 입력. 비밀값·게이트웨이 설정은 넣지 않는다(워커의 Spring 빈이 가진다) — 히스토리에 남는 것은 이것뿐이다.
 * [id] 는 배포 기록(deployment.id), [image] 는 kustomization images[].name.
 */
data class DeployInput(val id: String, val service: String, val image: String, val tag: String, val by: String)

/**
 * 배포 한 건 = 이 워크플로의 한 실행. 워크플로 ID 는 `deploy-<service>`(TemporalLinks) 라 서비스당 동시에 하나만 돈다.
 * 3단계(카나리)는 여기에 Signal(promote/abort) 과 대기를 더한다.
 */
@WorkflowInterface
interface DeployWorkflow {
    @WorkflowMethod
    fun run(input: DeployInput)
}
