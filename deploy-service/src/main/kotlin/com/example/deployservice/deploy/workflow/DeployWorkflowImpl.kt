package com.example.deployservice.deploy.workflow

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

/**
 * COMMIT → SYNC → ROLLOUT. 예전 DeploymentExecutor.run 과 단계·문구·타이밍이 같지만, 기다림은 Temporal 타이머이고 상태는 Temporal 이 들고 있다 —
 * deploy-service 파드가 죽어도(자기 자신을 배포할 때 포함) 새 파드의 워커가 중단된 단계부터 이어서 간다.
 *
 * 결정성 규칙: 시계는 [Workflow.currentTimeMillis], 대기는 [Workflow.sleep], 모든 I/O 는 액티비티. 기록(MySQL)은 로컬 액티비티 [RecordActivities].
 * 실패하면 기록을 FAILED 로 닫은 뒤 워크플로도 같은 메시지의 ApplicationFailure(type deploy_failure)로 끝낸다 — 콘솔 기록이 진실이고 Temporal UI 에서도 빨갗게 보인다.
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
        const val FAILURE_TYPE = "deploy_failure"
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
            val seen = pollUntil(ARGO_REVISION_WAIT, POLL) { if (argo.refreshSeesRevision(out.revision)) true else null } ?: false
            if (!seen) log.warn("argo cd did not report revision {} within {}; syncing anyway", out.revision.take(7), ARGO_REVISION_WAIT)
            val requestedAt = Workflow.currentTimeMillis()
            argo.sync(input.service, out.revision)
            records.message(id, Step.SYNC, "동기화 중 (${out.revision.take(7)})")
            val op = pollUntil(ARGO_SYNC_TIMEOUT, POLL) { argo.operation(out.revision, requestedAt)?.takeIf { it.terminal } }
                ?: fail("Argo CD 동기화가 ${ARGO_SYNC_TIMEOUT.toMinutes()}분 안에 끝나지 않았습니다.")
            if (op.phase != "Succeeded") fail("Argo CD 동기화 ${op.phase}: ${op.message ?: "(메시지 없음)"}")
            records.finishStep(id, Step.SYNC, Progress.SYNC_DONE, "동기화 완료")

            // ---- 3. ROLLOUT ------------------------------------------------------------------------------------------------
            step = Step.ROLLOUT
            records.startStep(id, Step.ROLLOUT, Progress.SYNC_DONE)
            var last: RolloutView? = null
            val probe = pollUntil(ROLLOUT_TIMEOUT, POD_POLL) {
                val p = kubernetes.rolloutStatus(input.service)
                if (p.view != last) {
                    // 기록은 값이 바뀔 때만(히스토리 이벤트 절약)
                    records.rollout(id, p.view, Progress.rollout(p.view.ready, p.view.desired))
                    records.message(id, Step.ROLLOUT, "준비 ${p.view.ready}/${p.view.desired}")
                    last = p.view
                }
                p.failingPod?.let { fail(it) }
                p.takeIf { it.rolledOut }
            } ?: fail("롤아웃이 ${ROLLOUT_TIMEOUT.toMinutes()}분 안에 끝나지 않았습니다.")
            records.finishStep(id, Step.ROLLOUT, Progress.DONE, "준비 ${probe.view.ready}/${probe.view.desired}")
            records.succeed(id)
            log.info("deployment {} {} → {} succeeded", id, input.service, input.tag)
        } catch (e: TemporalFailure) {
            val message = messageOf(e)
            log.warn("deployment {} failed at {}: {}", id, step, message)
            records.fail(id, step, message)
            throw ApplicationFailure.newNonRetryableFailure(message, FAILURE_TYPE)
        }
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
