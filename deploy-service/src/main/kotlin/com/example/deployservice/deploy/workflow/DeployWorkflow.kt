package com.example.deployservice.deploy.workflow

import com.example.deployservice.deploy.DeployMode
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * 배포 워크플로 입력. 비밀값·게이트웨이 설정은 넣지 않는다(워커의 Spring 빈이 가진다) — 히스토리에 남는 것은 이것뿐이다.
 * [id] 는 배포 기록(deployment.id), [image] 는 kustomization images[].name.
 */
data class DeployInput(val id: String, val service: String, val image: String, val tag: String, val by: String, val mode: DeployMode = DeployMode.DIRECT)

/**
 * 배포 한 건 = 이 워크플로의 한 실행. 워크플로 ID 는 `deploy-<service>`(TemporalLinks) 라 서비스당 동시에 하나만 돈다.
 * 카나리(2026-11-15): [promote]/[abort] Signal 로 사람이 단계를 정한다.
 */
@WorkflowInterface
interface DeployWorkflow {
    @WorkflowMethod
    fun run(input: DeployInput)

    /** 카나리: 다음 단계로(사람의 결정). 대기 중이 아니면 무시된다. */
    @SignalMethod
    fun promote(by: String)

    /** 카나리: 중단 — 옛 버전으로 되돌리고 git 태그도 원복. 진행보다 우선. */
    @SignalMethod
    fun abort(by: String)
}
