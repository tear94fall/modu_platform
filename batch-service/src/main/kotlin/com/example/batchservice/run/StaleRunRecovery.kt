package com.example.batchservice.run

import com.example.batchservice.job.BatchJobStore
import com.example.batchservice.job.RunStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import java.time.Clock

/**
 * 기동 때 RUNNING 으로 남은 회차를 FAILED 로 닫는다. 회차를 돌리던 스레드는 프로세스와 함께 죽었으므로(재시작·롤링 배포) 그 행은 진행 상태를 잃은 것이다.
 * 서비스 쪽 잡은 끝까지 돌았을 수 있다 — 그건 OpenSearch 의 `job.run` 이벤트(같은 requestId)로 확인한다. [CronScheduler] 보다 먼저 돈다(@Order).
 */
@Order(10)
class StaleRunRecovery(private val store: BatchJobStore, private val clock: Clock) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val stale = store.runningRuns()
        if (stale.isEmpty()) return
        val at = clock.instant()
        stale.forEach { r ->
            store.updateRun(r.id) { if (it.status == RunStatus.RUNNING) it.copy(status = RunStatus.FAILED, finishedAt = at, error = MESSAGE) else it }
        }
        log.warn("marked {} stale RUNNING batch run(s) FAILED after restart: {}", stale.size, stale.map { it.id })
    }

    companion object {
        const val MESSAGE = "batch-service 가 재시작돼 진행 상태를 잃었습니다. 서비스 쪽 실행 결과는 OpenSearch 의 job.run 이벤트(같은 requestId)로 확인하세요."
    }
}
