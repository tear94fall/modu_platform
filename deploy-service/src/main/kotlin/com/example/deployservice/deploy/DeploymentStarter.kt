package com.example.deployservice.deploy

import com.example.deployservice.api.ApiException
import com.example.deployservice.deploy.workflow.DeployInput
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.client.WorkflowExecutionAlreadyStarted
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * 배포 시작. 수문장 둘:
 * 1. `deployment` 표의 유니크 키(running_service) — 콘솔이 읽는 기록에 RUNNING 이 서비스당 하나만.
 * 2. 워크플로 ID `deploy-<service>` — Temporal 이 같은 ID 의 두 번째 실행을 거절한다(실제 실행이 하나만).
 *
 * 고아 RUNNING 기록: 워크플로가 기록을 못 닫고 사라지는 길이 있다(실행 타임아웃, `temporal workflow terminate`, 기록 DB 다운, 스레드 시절 기록).
 * 그러면 1 이 영원히 409 를 낸다. 그래서 1 이 거절하면 Temporal 에 묻고 — 기록이 가리키는 run(workflowRunId)이 끝났거나 없으면 — 그 RUNNING 행을
 * FAILED 로 닫고 한 번 더 넣는다. 기동 때 훑는 작업은 없다.
 *
 * 레이스 보호: A 가 행을 넣고 temporal.start 가 돌아오기 전에 B 가 들어오면 Temporal 에는 아직 A 의 실행이 없다. 그 행은 runId 가 없고 "어리다"
 * ([ORPHAN_GRACE] 안) — 고아로 보지 않고 409. Starter 는 start 실패 때 자기 행을 직접 닫으므로, runId 없는 RUNNING 행이 유예를 넘겨 남아 있다면
 * 시작 도중 파드가 죽은 것이고 그때는 닫아도 된다.
 */
class DeploymentStarter(
    private val temporal: TemporalGateway,
    private val store: DeploymentStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val ORPHAN_CLOSED = "워크플로가 없어 닫음(재시작·종료·타임아웃)"
        const val ALREADY_RUNNING = "같은 서비스의 배포 워크플로가 이미 실행 중입니다."
        const val NOT_REGISTERED = "Temporal 에 배포를 등록하지 못했습니다"
        /** runId 없는 RUNNING 행을 "시작 진행 중" 으로 봐 주는 시간. temporal.start 는 보통 수십 ms, 길어도 몇 초다. */
        val ORPHAN_GRACE: Duration = Duration.ofMinutes(2)
    }

    fun start(record: DeploymentRecord, image: String): DeploymentRecord {
        val workflowId = TemporalLinks.workflowId(record.service)
        if (!store.addIfNoneRunning(record)) {
            reconcileOrphans(record.service, workflowId)
            if (!store.addIfNoneRunning(record)) throw inProgress(record.service)
        }
        val runId = try {
            temporal.start(workflowId, DeployInput(record.id, record.service, image, record.tag, record.by))
        } catch (e: WorkflowExecutionAlreadyStarted) {
            close(record.id, ALREADY_RUNNING)
            throw inProgress(record.service)
        } catch (e: Exception) {
            log.error("deployment {} could not be registered in temporal", record.id, e)
            close(record.id, "$NOT_REGISTERED: ${e.message ?: e.javaClass.simpleName}")
            throw unavailable(e)
        }
        log.info("deployment {} {} → {} by {} started (workflow {} run {})", record.id, record.service, record.tag, record.by, workflowId, runId)
        return store.update(record.id) { it.copy(workflowRunId = runId) } ?: record.copy(workflowRunId = runId)
    }

    /**
     * 그 서비스의 RUNNING 기록 중 "살아 있지 않은" 것만 FAILED 로 닫는다.
     * - 최신 실행이 RUNNING 이면 아무것도 닫지 않는다(진짜 배포 중).
     * - runId 가 있는 행: 그 run 이 끝났거나 없으면 닫는다.
     * - runId 가 없는 행: 유예([ORPHAN_GRACE])를 넘긴 것만 닫는다(어린 행은 시작 진행 중일 수 있다).
     * Temporal 조회 자체가 실패하면 503 — 추측으로 닫지 않는다.
     */
    private fun reconcileOrphans(service: String, workflowId: String) {
        val orphans = try {
            if (temporal.status(workflowId, null) == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) return
            val now = clock.instant()
            store.running(service).filter { r ->
                val runId = r.workflowRunId
                if (runId != null) temporal.status(workflowId, runId) != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
                else Duration.between(r.startedAt, now) > ORPHAN_GRACE
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw unavailable(e)
        }
        orphans.forEach { close(it.id, ORPHAN_CLOSED) }
        if (orphans.isNotEmpty()) log.warn("closed {} orphan RUNNING record(s) of {} (workflow {})", orphans.size, service, workflowId)
    }

    private fun close(id: String, error: String) {
        val at = clock.instant()
        runCatching {
            store.update(id) { r -> if (r.status != DeploymentStatus.RUNNING) r else r.copy(status = DeploymentStatus.FAILED, error = error, finishedAt = at) }
        }.onFailure { log.error("deployment {} could not be closed ({})", id, error, it) }
    }

    private fun inProgress(service: String) = ApiException.conflict("deploy_in_progress", "$service 는 이미 배포 중입니다.")

    private fun unavailable(e: Exception) = ApiException.unavailable("temporal_unavailable", "Temporal 에 연결할 수 없어 배포를 시작하지 못했습니다: ${e.message ?: e.javaClass.simpleName}")
}
