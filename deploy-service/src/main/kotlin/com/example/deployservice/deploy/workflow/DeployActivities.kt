package com.example.deployservice.deploy.workflow

import com.example.deployservice.deploy.RolloutView
import com.example.deployservice.deploy.Step
import io.temporal.activity.ActivityInterface

/**
 * 배포 워크플로([DeployWorkflow])가 부르는 액티비티. 외부 시스템(GitHub·Argo CD·k8s)과 기록 DB 는 전부 여기 뒤에 있고, 워크플로 코드는
 * 이 인터페이스만 본다(결정성). 구현은 [DeployActivitiesImpl.kt] — 기존 게이트웨이를 감싸 실패를 Temporal 의 재시도 가능/불가 실패로 바꾼다.
 */

/** 커밋 한 번의 결과. [commitSha] 가 null 이면 "이미 같은 태그"(커밋 없이 브랜치 HEAD 를 [revision] 으로). */
data class CommitOutcome(val revision: String, val commitSha: String?, val commitUrl: String?, val previousTag: String, val message: String)

/** Argo CD 작업 상태 중 워크플로가 보는 것. */
data class ArgoOp(val phase: String, val message: String?, val terminal: Boolean)

/** 롤아웃 한 번 들여다본 결과. [failingPod] 는 새 ReplicaSet 파드가 CrashLoopBackOff 등이면 그 메시지(배포 실패 사유), 아니면 null. */
data class RolloutProbe(val view: RolloutView, val rolledOut: Boolean, val failingPod: String?)

@ActivityInterface
interface CommitActivities {
    /**
     * kustomization.yaml 을 읽어 [image] 의 newTag 를 [tag] 로 바꿔 PUT 한다(한 번). 같은 태그면 PUT 없이 브랜치 HEAD.
     * GitHub 의 sha 충돌(409/422)은 type `sha_conflict` 의 **재시도 가능** 실패 — 워크플로의 재시도 정책(5회)이 처음부터 다시 읽게 한다.
     * 그 밖의 실패(읽기 실패·PUT 오류)는 재시도하지 않는다.
     */
    fun commitTag(service: String, image: String, tag: String, by: String): CommitOutcome
}

@ActivityInterface
interface ArgoActivities {
    /** refresh 뒤 Argo 가 본 revision 이 [revision] 인가. */
    fun refreshSeesRevision(revision: String): Boolean

    /** 그 Deployment 하나만 [revision] 으로 Sync 요청. */
    fun sync(service: String, revision: String)

    /** 현재 작업이 우리 것(revision 일치 또는 [requestedAtEpochMs]-5초 이후 시작)이면 그 상태, 아니면 null. */
    fun operation(revision: String, requestedAtEpochMs: Long): ArgoOp?
}

@ActivityInterface
interface KubernetesActivities {
    /** Deployment·파드 스냅샷. Deployment 가 없으면 재시도 불가 실패. */
    fun rolloutStatus(service: String): RolloutProbe
}

/** 배포 기록(MySQL deployment) 쓰기 — 로컬 액티비티로 부른다. 시각은 구현의 Clock. */
@ActivityInterface
interface RecordActivities {
    fun startStep(id: String, step: Step, percent: Int)
    fun message(id: String, step: Step, text: String)
    fun finishStep(id: String, step: Step, percent: Int, text: String)
    fun previousTag(id: String, tag: String)
    fun commit(id: String, sha: String, url: String)
    fun rollout(id: String, view: RolloutView, percent: Int)
    fun succeed(id: String)
    /** RUNNING 인 기록만 FAILED 로 닫는다(이미 닫힌 기록은 건드리지 않는다). */
    fun fail(id: String, step: Step, error: String)
}
