package com.example.deployservice.deploy

import java.time.Instant

enum class DeploymentStatus { RUNNING, SUCCEEDED, FAILED }

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
