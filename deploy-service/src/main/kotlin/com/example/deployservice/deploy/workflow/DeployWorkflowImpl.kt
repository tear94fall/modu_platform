package com.example.deployservice.deploy.workflow

import com.example.deployservice.deploy.CanaryHistory
import com.example.deployservice.deploy.CanaryView
import com.example.deployservice.deploy.DeployMode
import com.example.deployservice.deploy.Progress
import com.example.deployservice.deploy.RolloutView
import com.example.deployservice.deploy.Step
import io.temporal.activity.ActivityOptions
import io.temporal.activity.LocalActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.failure.TemporalFailure
import io.temporal.failure.TimeoutFailure
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Workflow
import java.time.Duration
import java.time.Instant

/**
 * COMMIT → SYNC → ROLLOUT. 예전 DeploymentExecutor.run 과 단계·문구·타이밍이 같지만, 기다림은 Temporal 타이머이고 상태는 Temporal 이 들고 있다 —
 * deploy-service 파드가 죽어도(자기 자신을 배포할 때 포함) 새 파드의 워커가 중단된 단계부터 이어서 간다.
 *
 * 결정성 규칙: 시계는 [Workflow.currentTimeMillis], 대기는 [Workflow.sleep]/[Workflow.await], 모든 I/O 는 액티비티. 기록(MySQL)은 로컬 액티비티 [RecordActivities].
 * 실패하면 기록을 FAILED 로 닫은 뒤 워크플로도 같은 메시지의 ApplicationFailure(type deploy_failure)로 끝낸다 — 콘솔 기록이 진실이고 Temporal UI 에서도 빨갗게 보인다.
 *
 * **버전(2026-11-15, 카나리)**: ROLLOUT 단계가 Deployment 폴링(v1)에서 Argo Rollouts 기준(v2: promoteFull 또는 단계 대기 + Signal)으로 바뀌었다.
 * 진행 중이던 실행(자기 배포 포함)이 새 코드로 재생되므로 [Workflow.getVersion] "rollout-v2" 로 가른다. v1 경로는 다음 워크플로 변경 때 지운다.
 * 워크플로 코드를 다시 바꿀 때는 같은 방식으로 getVersion 을 더하거나, deploy-deploy-service 가 실행 중이 아닐 때 배포한다(README).
 */
@WorkflowImpl(taskQueues = ["\${deploy.temporal.task-queue}"])
class DeployWorkflowImpl : DeployWorkflow {

    companion object {
        val ARGO_SYNC_TIMEOUT: Duration = Duration.ofMinutes(10)
        val ROLLOUT_TIMEOUT: Duration = Duration.ofMinutes(10)
        /** 커밋 직후 Argo 가 새 revision 을 볼 때까지 refresh 를 되풀이하는 최대 시간. 못 봐도 Sync 는 요청한다. */
        val ARGO_REVISION_WAIT: Duration = Duration.ofSeconds(60)
        val POLL: Duration = Duration.ofSeconds(3)
        val POD_POLL: Duration = Duration.ofSeconds(2)
        /** 카나리 대기 중 heartbeat 간격(새 파드 CrashLoop 감지·기록 스냅샷) 과 상한 — 1시간 × 168 = 7일. 넘기면 시스템이 중단 경로를 밟는다. */
        val WAIT_HEARTBEAT: Duration = Duration.ofHours(1)
        const val WAIT_MAX_HEARTBEATS = 168
        const val FAILURE_TYPE = "deploy_failure"
        const val SYSTEM = "system"
        const val WAIT_EXPIRED = "7일 대기 상한 — 시스템이 옛 버전으로 되돌림"
        const val ROLLOUT_V2 = "rollout-v2"
    }

    private val log = Workflow.getLogger(DeployWorkflowImpl::class.java)

    private val records = Workflow.newLocalActivityStub(
        RecordActivities::class.java,
        LocalActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build(),
    )

    /** sha 충돌(type sha_conflict, 재시도 가능)만 다섯 번까지 처음부터 다시 — 예전 commitWithShaRetry 와 같은 횟수. */
    private val commit = Workflow.newActivityStub(
        CommitActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .setRetryOptions(
                RetryOptions.newBuilder().setMaximumAttempts(5).setInitialInterval(Duration.ofMillis(200)).setBackoffCoefficient(2.0).setMaximumInterval(Duration.ofSeconds(2)).build(),
            )
            .build(),
    )

    private val argo = Workflow.newActivityStub(
        ArgoActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build()).build(),
    )

    private val kubernetes = Workflow.newActivityStub(
        KubernetesActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(15)).setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build()).build(),
    )

    private var step: Step = Step.COMMIT

    /** 사람의 결정(Signal). PROMOTE 는 소비 뒤 null 로 돌아간다. */
    private data class Action(val kind: String, val by: String)
    private var pendingAction: Action? = null

    override fun promote(by: String) {
        if (pendingAction == null) pendingAction = Action("PROMOTE", by)
    }

    override fun abort(by: String) {
        pendingAction = Action("ABORT", by)   // 중단은 진행보다 우선한다
    }

    override fun run(input: DeployInput) {
        val id = input.id
        try {
            // ---- 1. COMMIT -------------------------------------------------------------------------------------------------
            step = Step.COMMIT
            records.startStep(id, Step.COMMIT, Progress.COMMIT_START)
            val out = commit.commitTag(input.service, input.image, input.tag, input.by)
            records.previousTag(id, out.previousTag)
            if (out.commitSha != null) records.commit(id, out.commitSha, out.commitUrl ?: "")
            records.finishStep(id, Step.COMMIT, Progress.COMMIT_DONE, out.message)

            // ---- 2. SYNC ---------------------------------------------------------------------------------------------------
            step = Step.SYNC
            records.startStep(id, Step.SYNC, Progress.COMMIT_DONE)
            syncRevision(id, input.service, out.revision)
            records.finishStep(id, Step.SYNC, Progress.SYNC_DONE, "동기화 완료")

            // ---- 3. ROLLOUT ------------------------------------------------------------------------------------------------
            step = Step.ROLLOUT
            records.startStep(id, Step.ROLLOUT, Progress.SYNC_DONE)
            val version = Workflow.getVersion(ROLLOUT_V2, Workflow.DEFAULT_VERSION, 1)
            val finished = if (version == Workflow.DEFAULT_VERSION) rolloutV1(id, input) else rolloutV2(id, input, out.previousTag)
            if (!finished) return   // ABORTED — 기록은 닫혔다
            records.succeed(id)
            log.info("deployment {} {} → {} succeeded", id, input.service, input.tag)
        } catch (e: TemporalFailure) {
            val message = messageOf(e)
            log.warn("deployment {} failed at {}: {}", id, step, message)
            records.fail(id, step, message)
            throw ApplicationFailure.newNonRetryableFailure(message, FAILURE_TYPE)
        }
    }

    /** Argo refresh 로 revision 이 보일 때까지(최대 60초) → Sync 요청 → 작업이 끝날 때까지(최대 10분). 실패는 fail(). */
    private fun syncRevision(id: String, service: String, revision: String) {
        val seen = pollUntil(ARGO_REVISION_WAIT, POLL) { if (argo.refreshSeesRevision(revision)) true else null } ?: false
        if (!seen) log.warn("argo cd did not report revision {} within {}; syncing anyway", revision.take(7), ARGO_REVISION_WAIT)
        val requestedAt = Workflow.currentTimeMillis()
        argo.sync(service, revision)
        records.message(id, Step.SYNC, "동기화 중 (${revision.take(7)})")
        val op = pollUntil(ARGO_SYNC_TIMEOUT, POLL) { argo.operation(revision, requestedAt)?.takeIf { it.terminal } }
            ?: fail("Argo CD 동기화가 ${ARGO_SYNC_TIMEOUT.toMinutes()}분 안에 끝나지 않았습니다.")
        if (op.phase != "Succeeded") fail("Argo CD 동기화 ${op.phase}: ${op.message ?: "(메시지 없음)"}")
    }

    // ---- ROLLOUT v1: Deployment 폴링(전환 전 실행의 재생용) --------------------------------------------------------------------

    private fun rolloutV1(id: String, input: DeployInput): Boolean {
        var last: RolloutView? = null
        val probe = pollUntil(ROLLOUT_TIMEOUT, POD_POLL) {
            val p = kubernetes.rolloutStatus(input.service)
            if (p.view != last) {
                records.rollout(id, p.view, Progress.rollout(p.view.ready, p.view.desired))
                records.message(id, Step.ROLLOUT, "준비 ${p.view.ready}/${p.view.desired}")
                last = p.view
            }
            p.failingPod?.let { fail(it) }
            p.takeIf { it.rolledOut }
        } ?: fail("롤아웃이 ${ROLLOUT_TIMEOUT.toMinutes()}분 안에 끝나지 않았습니다.")
        records.finishStep(id, Step.ROLLOUT, Progress.DONE, "준비 ${probe.view.ready}/${probe.view.desired}")
        return true
    }

    // ---- ROLLOUT v2: Argo Rollouts ----------------------------------------------------------------------------------------

    /** true = 완료(SUCCEEDED 로 닫을 것), false = 중단(ABORTED 로 이미 닫힘). */
    private fun rolloutV2(id: String, input: DeployInput, previousTag: String): Boolean {
        if (input.mode == DeployMode.DIRECT) {
            // 단계가 있어도 한 번에(파드 1개면 단계가 없어 no-op). Sync 직후의 promoteFull 은 컨트롤러가 새 템플릿을 보기 전에 닿으면 지워지므로,
            // 폴링 중 단계에서 멈춘 것을 보면 다시 보낸다(멱등) — 그러지 않으면 50% 에서 멈춘 채 10분 타임아웃으로 실패한다(리뷰 2026-11-15).
            kubernetes.promoteFull(input.service)
            val probe = waitRollout(id, input.service, canary = false) { p ->
                if (p.pausedAtStep) kubernetes.promoteFull(input.service)
                p.rolledOut
            }
            records.finishStep(id, Step.ROLLOUT, Progress.DONE, "준비 ${probe.ready}/${probe.desired}")
            return true
        }

        // CANARY: 단계마다 멈춰 사람의 결정을 기다린다
        var canary = CanaryView(totalSteps = 0, currentStep = 0, weight = 0, waiting = false)
        records.canary(id, canary)
        var probe: RolloutProbe2
        var heartbeats = 0   // 배포 전체의 대기 시간(단계마다 리셋하면 실행 타임아웃 8일을 넘길 수 있다)
        while (true) {
            probe = waitRollout(id, input.service, canary = true) { it.rolledOut || it.pausedAtStep }
            canary = canary.copy(totalSteps = probe.totalSteps, currentStep = probe.currentStep, weight = probe.currentWeight,
                canaryPods = probe.canaryPods, stablePods = probe.stablePods)
            if (probe.rolledOut) break

            canary = canary.copy(waiting = true, waitingSince = Instant.ofEpochMilli(Workflow.currentTimeMillis()))
            records.canary(id, canary)
            pendingAction = null
            while (pendingAction == null) {
                if (Workflow.await(WAIT_HEARTBEAT) { pendingAction != null }) break
                // 1시간마다: 새 파드가 죽어 있으면 실패, 기록 스냅샷 갱신, 7일이면 시스템 중단
                val p = kubernetes.rolloutStatus2(input.service)
                records.rollout(id, p.view(), Progress.rollout(p.ready, p.desired))
                p.failingPod?.let { fail(it) }
                if (++heartbeats >= WAIT_MAX_HEARTBEATS) pendingAction = Action("ABORT", SYSTEM)
            }
            val action = pendingAction!!
            val entry = CanaryHistory(step = canary.currentStep, weight = canary.weight, action = action.kind, by = action.by, at = Instant.ofEpochMilli(Workflow.currentTimeMillis()))
            records.canaryHistory(id, entry)
            canary = canary.copy(waiting = false, history = canary.history + entry)
            if (action.kind == "ABORT") {
                abortCanary(id, input, previousTag, action)
                return false
            }
            kubernetes.promote(input.service)
            pendingAction = null
        }
        records.finishStep(id, Step.ROLLOUT, Progress.DONE, "준비 ${probe.ready}/${probe.desired}")
        return true
    }

    /** 10분/2초 폴링. 기록은 값이 바뀔 때만. 새 RS 파드의 CrashLoop 은 즉시 실패. */
    private fun waitRollout(id: String, service: String, canary: Boolean, done: (RolloutProbe2) -> Boolean): RolloutProbe2 {
        var last: RolloutView? = null
        return pollUntil(ROLLOUT_TIMEOUT, POD_POLL) {
            val p = kubernetes.rolloutStatus2(service)
            val view = p.view()
            if (view != last) {
                records.rollout(id, view, Progress.rollout(p.ready, p.desired))
                records.message(id, Step.ROLLOUT, if (!canary) "준비 ${p.ready}/${p.desired}" else "카나리 ${p.currentWeight}% · 준비 ${p.ready}/${p.desired}")
                last = view
            }
            p.failingPod?.let { fail(it) }
            p.takeIf(done)
        } ?: fail("롤아웃이 ${ROLLOUT_TIMEOUT.toMinutes()}분 안에 끝나지 않았습니다.")
    }


    /**
     * abort → **먼저** 이전 태그 커밋 + Sync(git 이 실행과 어긋난 채 남지 않게 — 그 뒤 무엇이 실패해도 git 은 옛 태그) → 템플릿이 옛 태그로 돌아와
     * Rollout 이 Healthy → 기록 ABORTED. 커밋이 실패하면 메시지에 git 이 새 태그로 남아 있음을 적는다(사람이 이전 태그로 다시 배포).
     */
    private fun abortCanary(id: String, input: DeployInput, previousTag: String, action: Action) {
        kubernetes.abort(input.service)
        records.message(id, Step.ROLLOUT, "중단 — git 을 옛 태그 $previousTag 로 되돌리는 중")
        val revert = try {
            commit.commitTag(input.service, input.image, previousTag, action.by)
        } catch (e: TemporalFailure) {
            fail("중단했지만 git 을 이전 태그로 되돌리지 못했습니다(${messageOf(e)}) — git 에는 새 태그 ${input.tag} 가 남아 있습니다. 이전 태그 $previousTag 로 다시 배포하세요.")
        }
        if (revert.commitSha != null) records.commit(id, revert.commitSha, revert.commitUrl ?: "")
        syncRevision(id, input.service, revert.revision)
        pollUntil(ROLLOUT_TIMEOUT, POD_POLL) {
            val p = kubernetes.rolloutStatus2(input.service)
            records.rollout(id, p.view(), Progress.rollout(p.ready, p.desired))
            p.takeIf { it.rolledOut }
        } ?: fail("중단 뒤 git 은 이전 태그 $previousTag 로 되돌렸지만 Rollout 이 ${ROLLOUT_TIMEOUT.toMinutes()}분 안에 Healthy 가 되지 않았습니다.")
        val reason = if (action.by == SYSTEM) WAIT_EXPIRED else "운영자가 중단(${action.by}) — 옛 태그 $previousTag 로 되돌림"
        records.aborted(id, action.by, reason)
        log.info("deployment {} {} aborted by {} — reverted to {}", id, input.service, action.by, previousTag)
    }

    private fun fail(message: String): Nothing = throw ApplicationFailure.newNonRetryableFailure(message, FAILURE_TYPE)

    /** 액티비티가 던진 ApplicationFailure 의 원문, 타임아웃이면 그 사실, 그 외는 예외 메시지. */
    private fun messageOf(e: TemporalFailure): String = when (e) {
        is ApplicationFailure -> e.originalMessage
        is ActivityFailure -> when (val cause = e.cause) {
            is ApplicationFailure -> cause.originalMessage
            is TimeoutFailure -> "${e.activityType} 액티비티가 제한 시간 안에 끝나지 않았습니다(${cause.timeoutType})"
            else -> cause?.message ?: "${e.activityType} 액티비티 실패"
        }
        else -> e.message ?: "실패"
    }

    /** [probe] 가 null 아닌 값을 돌려줄 때까지 [interval] 마다 되풀이한다. [timeout] 을 넘기면 null. 시계·대기는 Temporal 의 것. */
    private fun <T> pollUntil(timeout: Duration, interval: Duration, probe: () -> T?): T? {
        val deadline = Workflow.currentTimeMillis() + timeout.toMillis()
        while (true) {
            probe()?.let { return it }
            if (Workflow.currentTimeMillis() >= deadline) return null
            Workflow.sleep(interval)
        }
    }
}
