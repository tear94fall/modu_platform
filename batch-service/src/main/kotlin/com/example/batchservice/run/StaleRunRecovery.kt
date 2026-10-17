package com.example.batchservice.run

import com.example.batchservice.config.BatchProperties
import com.example.batchservice.job.BatchJobRun
import com.example.batchservice.job.BatchJobStore
import com.example.batchservice.job.RunStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ScheduledFuture

/**
 * 기한이 지나도 RUNNING 인 회차를 FAILED 로 닫는다. 기한 = 시작 + 유효 타임아웃 + 연결 시간 + 여유([GRACE]) — [BatchRunner.deadline].
 *
 * "재시작했으니 RUNNING 은 전부 죽었다" 로 보지 않는다. 롤링 배포(maxSurge 1) 중에는 새 파드가 뜰 때 옛 파드가 아직 그 회차를 정상적으로 돌리고 있어서,
 * 그걸 닫으면 이력이 FAILED 로 찍히다 옛 파드가 끝나며 SUCCEEDED 로 뒤집히고 그 사이 같은 잡이 두 번 시작될 수 있다. 기한을 넘긴 행만 닫으면
 * 둘 다 피한다. 기동 때 한 번([run]) 그리고 [INTERVAL] 마다([sweep]) — [BatchRunner.complete] 가 닫기에 실패해 남은 고아 RUNNING 도 이걸로 풀린다.
 * 서비스 쪽 잡은 끝까지 돌았을 수 있다 — 그건 OpenSearch 의 `job.run` 이벤트(같은 requestId)로 확인한다. [CronScheduler] 보다 먼저 돈다(@Order).
 */
@Order(10)
class StaleRunRecovery(
    private val store: BatchJobStore,
    private val properties: BatchProperties,
    private val clock: Clock,
    private val pool: ThreadPoolTaskScheduler,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)
    private var periodic: ScheduledFuture<*>? = null

    override fun run(args: ApplicationArguments) {
        sweep()
        periodic = pool.scheduleAtFixedRate({ runCatching { sweep() }.onFailure { log.error("stale run sweep failed", it) } }, INTERVAL)
    }

    /** 기한을 넘긴 RUNNING 회차를 닫고 돌려준다. */
    fun sweep(): List<BatchJobRun> {
        val now = clock.instant()
        val stale = store.runningRuns().filter { r -> BatchRunner.deadline(r, store.job(r.batchJobId), properties.run, GRACE).isBefore(now) }
        if (stale.isEmpty()) return emptyList()
        val closed = stale.mapNotNull { r ->
            store.updateRun(r.id) { if (it.status == RunStatus.RUNNING) it.copy(status = RunStatus.FAILED, finishedAt = now, error = MESSAGE) else it }
        }
        log.warn("closed {} stale RUNNING batch run(s) as FAILED: {}", closed.size, closed.map { it.id })
        return closed
    }

    fun scheduled(): Boolean = periodic?.let { !it.isCancelled } ?: false

    companion object {
        val GRACE: Duration = Duration.ofMinutes(1)
        val INTERVAL: Duration = Duration.ofMinutes(1)
        const val MESSAGE = "타임아웃이 지나도 끝나지 않아 batch-service 가 닫았습니다(재시작으로 진행 상태를 잃었을 수 있음). 서비스 쪽 실행 결과는 OpenSearch 의 job.run 이벤트(같은 requestId)로 확인하세요."
    }
}
