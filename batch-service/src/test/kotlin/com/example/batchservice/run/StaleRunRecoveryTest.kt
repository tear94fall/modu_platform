package com.example.batchservice.run

import com.example.batchservice.config.BatchProperties
import com.example.batchservice.job.BatchJob
import com.example.batchservice.job.BatchJobRun
import com.example.batchservice.job.InMemoryBatchJobStore
import com.example.batchservice.job.RunStatus
import com.example.batchservice.job.RunTrigger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * RUNNING 으로 남은 회차 정리. "재시작했으니 전부 FAILED" 가 아니라 **나이**로 본다 — 롤링 배포 중에는 옛 파드가 아직 정상적으로 돌리고 있을 수 있어서다.
 * 기준은 시작 + 유효 타임아웃(잡 정의, 없으면 기본값) + 연결 시간 + 여유 1분. 기동 때 한 번, 그 뒤 1분마다(complete 가 던져 영영 RUNNING 으로 남은 행도 이걸로 풀린다).
 */
class StaleRunRecoveryTest {
    private val store = InMemoryBatchJobStore()
    private val now = Instant.parse("2026-10-17T03:00:00Z")
    private val props = BatchProperties(run = BatchProperties.Run(connectTimeoutSeconds = 5, defaultTimeoutSeconds = 600, maxTimeoutSeconds = 3600))
    private val pool = ThreadPoolTaskScheduler().apply { setPoolSize(1); initialize() }
    private val recovery = StaleRunRecovery(store, props, Clock.fixed(now, ZoneOffset.UTC), pool)

    @AfterEach
    fun shutdown() = pool.shutdown()

    private fun running(id: String, job: BatchJob, startedAgoSeconds: Long) =
        BatchJobRun(id, job.id!!, job.service, job.jobName, RunTrigger.CRON, null, RunStatus.RUNNING, now.minusSeconds(startedAgoSeconds))

    @Test
    fun `closes only RUNNING runs older than start + timeout + connect + grace, with the stale message`() {
        val job = store.saveJob(BatchJob(null, "commerce-service", "a", cron = "0 * * * * *", enabled = true, timeoutSeconds = 120))
        val other = store.saveJob(BatchJob(null, "commerce-service", "b", cron = "0 * * * * *", enabled = true, timeoutSeconds = 120))
        // 기한 = 120 + 5 + 60 = 185초
        store.addRun(running("job-0000000000000001", job, 184))
        store.addRun(running("job-0000000000000002", other, 186))
        store.addRun(BatchJobRun("job-0000000000000003", job.id!!, "commerce-service", "a", RunTrigger.MANUAL, "x", RunStatus.SUCCEEDED, now.minusSeconds(999), now))

        val closed = recovery.sweep()

        assertEquals(RunStatus.RUNNING, store.run("job-0000000000000001")!!.status, "기한 안이면 아직 돌고 있는 것으로 본다(롤링 배포 중 옛 파드)")
        assertEquals(RunStatus.SUCCEEDED, store.run("job-0000000000000003")!!.status)
        val stale = store.run("job-0000000000000002")!!
        assertEquals(RunStatus.FAILED, stale.status)
        assertEquals(now, stale.finishedAt)
        assertEquals(StaleRunRecovery.MESSAGE, stale.error)
        assertEquals(listOf("job-0000000000000002"), closed.map { it.id })
    }

    @Test
    fun `a run whose job was deleted uses the default timeout`() {
        store.addRun(BatchJobRun("job-0000000000000004", 7, "commerce-service", "gone", RunTrigger.CRON, null, RunStatus.RUNNING, now.minusSeconds(600 + 5 + 60 - 1)))
        store.addRun(BatchJobRun("job-0000000000000005", 7, "commerce-service", "gone", RunTrigger.CRON, null, RunStatus.RUNNING, now.minusSeconds(600 + 5 + 60 + 1)).copy(batchJobId = 8))

        recovery.sweep()

        assertEquals(RunStatus.RUNNING, store.run("job-0000000000000004")!!.status)
        assertEquals(RunStatus.FAILED, store.run("job-0000000000000005")!!.status)
    }

    @Test
    fun `startup sweeps once and schedules the periodic sweep`() {
        store.addRun(BatchJobRun("job-0000000000000006", 7, "commerce-service", "gone", RunTrigger.CRON, null, RunStatus.RUNNING, now.minusSeconds(100_000)))

        recovery.run(DefaultApplicationArguments())

        assertEquals(RunStatus.FAILED, store.run("job-0000000000000006")!!.status)
        assertTrue(recovery.scheduled(), "1분 주기 정리가 걸려 있다")
    }
}
