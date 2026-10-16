package com.example.batchservice.run

import com.example.batchservice.job.BatchJobRun
import com.example.batchservice.job.InMemoryBatchJobStore
import com.example.batchservice.job.RunStatus
import com.example.batchservice.job.RunTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 재시작 뒤 RUNNING 으로 남은 회차는 진행 상태를 잃었다 — FAILED 로 닫는다. */
class StaleRunRecoveryTest {
    private val store = InMemoryBatchJobStore()
    private val now = Instant.parse("2026-10-17T03:00:00Z")

    @Test
    fun `closes RUNNING runs as FAILED with the restart message`() {
        store.addRun(BatchJobRun("job-0000000000000001", 1, "commerce-service", "a", RunTrigger.CRON, null, RunStatus.RUNNING, now.minusSeconds(60)))
        store.addRun(BatchJobRun("job-0000000000000002", 2, "commerce-service", "b", RunTrigger.MANUAL, "x", RunStatus.SUCCEEDED, now.minusSeconds(60), now))

        StaleRunRecovery(store, Clock.fixed(now, ZoneOffset.UTC)).run(DefaultApplicationArguments())

        val stale = store.run("job-0000000000000001")!!
        assertEquals(RunStatus.FAILED, stale.status)
        assertEquals(now, stale.finishedAt)
        assertEquals(StaleRunRecovery.MESSAGE, stale.error)
        assertEquals(RunStatus.SUCCEEDED, store.run("job-0000000000000002")!!.status)
    }
}
