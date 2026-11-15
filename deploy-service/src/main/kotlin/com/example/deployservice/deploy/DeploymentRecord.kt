package com.example.deployservice.deploy

import java.time.Instant

/** ABORTED = 카나리 배포를 사람이(또는 7일 상한에 시스템이) 중단해 옛 버전으로 되돌린 것 — 실패가 아니다. */
enum class DeploymentStatus { RUNNING, SUCCEEDED, FAILED, ABORTED }

/** DIRECT = 한 번에 끝까지(기존 "배포"), CANARY = 단계마다 멈춰 사람이 다음 단계/중단을 정한다(2026-11-15). */
enum class DeployMode { DIRECT, CANARY }

/** 카나리 단계 이력 한 줄 — 누가 언제 몇 % 에서 무엇을(PROMOTE | ABORT) 했는가. */
data class CanaryHistory(val step: Int, val weight: Int, val action: String, val by: String, val at: Instant)

/** 조회 시점에 k8s 를 읽은 라이브 상태(저장하지 않는다). 대기 중 콘솔이 새/옛 파드 표를 그린다. */
/** [stepWeights] 는 Rollout 의 단계 가중치 — 콘솔이 "다음 단계 → W%" 를 계산한다(다음 = stepWeights[currentStep], 없으면 100). */
data class RolloutLive(val phase: String, val weight: Int, val pods: List<PodView>, val ready: Int, val desired: Int, val stepWeights: List<Int> = emptyList())

/**
 * 카나리 진행 상태(deployment.canary_json). [currentStep] 은 1부터, 0 = 첫 단계 전. [waiting] 이면 사람의 결정을 기다리는 중([waitingSince] 부터).
 * [canaryPods]/[stablePods] 는 대기 시작 시점의 새/옛 파드 이름(에러 로그 조회·화면 표). [live] 는 조회 시점 값으로 채워지며 저장되지 않는다.
 */
data class CanaryView(
    val totalSteps: Int,
    val currentStep: Int,
    val weight: Int,
    val waiting: Boolean,
    val waitingSince: Instant? = null,
    val canaryPods: List<String> = emptyList(),
    val stablePods: List<String> = emptyList(),
    val history: List<CanaryHistory> = emptyList(),
    val live: RolloutLive? = null,
)

/** 배포 단계. 순서대로 COMMIT → SYNC → ROLLOUT, 끝나면 DONE. */
enum class Step { COMMIT, SYNC, ROLLOUT, DONE }

enum class StepStatus { PENDING, RUNNING, SUCCEEDED, FAILED }

data class StepRecord(
    val name: Step,
    val status: StepStatus = StepStatus.PENDING,
    val message: String? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
)

data class PodView(val name: String, val phase: String, val ready: Boolean, val reason: String)

data class RolloutView(val desired: Int, val updated: Int, val ready: Int, val available: Int, val pods: List<PodView>)

data class CommitView(val sha: String, val url: String)

/**
 * 배포 한 건의 기록(불변). 상태 변화는 copy 로 만들어 [DeploymentStore] 에 다시 넣는다 — 어느 스레드에서 읽어도 일관된 스냅샷이다.
 * JSON 모양이 그대로 GET /api-system/deploy/deployments/{id} 의 응답이다.
 */
data class DeploymentRecord(
    val id: String,
    val service: String,
    val tag: String,
    val previousTag: String? = null,
    val by: String,
    /** 배포자 원래 식별자(게이트웨이 X-Auth-User-Id = 구글 sub). by 는 member-service 로 푼 이름·이메일. */
    val byId: String? = null,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val status: DeploymentStatus = DeploymentStatus.RUNNING,
    val step: Step = Step.COMMIT,
    val percent: Int = Progress.COMMIT_START,
    val steps: List<StepRecord> = listOf(StepRecord(Step.COMMIT), StepRecord(Step.SYNC), StepRecord(Step.ROLLOUT)),
    val rollout: RolloutView? = null,
    val commit: CommitView? = null,
    val error: String? = null,
    /** Temporal 워크플로 실행 id(deployment.workflow_run_id). 워크플로 ID 는 `deploy-<service>`([TemporalLinks]). 옛 기록·스레드 시절 기록은 null. */
    val workflowRunId: String? = null,
    /** 콘솔 "Temporal 에서 보기" 링크 — 저장하지 않는다. 서비스 계층이 [TemporalLinks.decorate] 로 채운다(실행 id 가 없으면 null). */
    val workflowUrl: String? = null,
    /** 배포 방식(deployment.mode). 옛 기록은 DIRECT. */
    val mode: DeployMode = DeployMode.DIRECT,
    /** 카나리 상태(deployment.canary_json). DIRECT 는 null. */
    val canary: CanaryView? = null,
) {
    fun withStep(name: Step, update: (StepRecord) -> StepRecord): DeploymentRecord =
        copy(steps = steps.map { if (it.name == name) update(it) else it })
}

/** 진행률 규칙: COMMIT 0→20, SYNC 20→50, ROLLOUT 50→100(준비된 복제본 비율), DONE 100. */
object Progress {
    const val COMMIT_START = 5
    const val COMMIT_DONE = 20
    const val SYNC_REQUESTED = 35
    const val SYNC_DONE = 50
    const val DONE = 100

    /** 롤아웃 중 진행률 = 50 + 50 × ready/desired (0..100 으로 자른다). desired 가 0 이면 더 기다릴 게 없으니 100. */
    fun rollout(ready: Int, desired: Int): Int {
        if (desired <= 0) return DONE
        val ratio = ready.coerceIn(0, desired).toDouble() / desired
        return (SYNC_DONE + 50 * ratio).toInt().coerceIn(SYNC_DONE, DONE)
    }
}
