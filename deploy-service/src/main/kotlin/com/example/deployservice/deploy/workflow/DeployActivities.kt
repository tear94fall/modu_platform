package com.example.deployservice.deploy.workflow

import com.example.deployservice.deploy.CanaryHistory
import com.example.deployservice.deploy.CanaryView
import com.example.deployservice.deploy.PodView
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

/**
 * Rollout(Argo Rollouts) 기준 롤아웃 상태(v2). Temporal 페이로드로 오가므로 **순수 필드만**(계산 프로퍼티가 있으면 Jackson 역직렬화가 실패해 워크플로 태스크가 무한 재시도된다 — 2026-11-15 교훈).
 * [currentStep] 은 1부터(0 = 첫 단계 전, totalSteps = 끝). [pods] 는 새/옛 구분이 있는 파드 표, [failingPod] 는 새 RS 파드의 CrashLoop 등.
 */
data class RolloutProbe2(
    val desired: Int,
    val updated: Int,
    val ready: Int,
    val available: Int,
    val phase: String,
    val totalSteps: Int,
    val currentStep: Int,
    val currentWeight: Int,
    val rolledOut: Boolean,
    val pausedAtStep: Boolean,
    val aborted: Boolean,
    val pods: List<PodView>,
    val canaryPods: List<String>,
    val stablePods: List<String>,
    val failingPod: String?,
) {
    fun view(): RolloutView = RolloutView(desired, updated, ready, available, pods)
}

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
    /** (v1, Deployment 기준 — getVersion 옛 경로가 쓴다) Deployment·파드 스냅샷. Deployment 가 없으면 재시도 불가 실패. */
    fun rolloutStatus(service: String): RolloutProbe

    /** (v2) Rollout·파드 스냅샷. Rollout 이 없으면 재시도 불가 실패. */
    fun rolloutStatus2(service: String): RolloutProbe2

    /** 일시정지 해제(다음 단계). 멱등. */
    fun promote(service: String)

    /** 남은 단계 전부 건너뛰고 끝까지(DIRECT). 멱등. */
    fun promoteFull(service: String)

    /** 중단 — stable ReplicaSet 으로. 멱등. */
    fun abort(service: String)
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

    /** 카나리 상태 덮어쓰기(history 는 기존 것을 유지하고 나머지만 바꾼다). */
    fun canary(id: String, view: CanaryView)

    /** 단계 이력 한 줄 추가 + waiting=false. */
    fun canaryHistory(id: String, entry: CanaryHistory)

    /** 중단으로 닫는다(status ABORTED, ROLLOUT 단계 FAILED 표시 + 메시지, finishedAt). RUNNING 인 기록만. */
    fun aborted(id: String, by: String, reason: String)
}
