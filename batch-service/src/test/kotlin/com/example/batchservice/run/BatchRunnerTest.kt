package com.example.batchservice.run

import com.example.batchservice.config.BatchProperties
import com.example.batchservice.config.ModuProperties
import com.example.batchservice.job.BatchJob
import com.example.batchservice.job.BatchJobRun
import com.example.batchservice.job.InMemoryBatchJobStore
import com.example.batchservice.job.RunStatus
import com.example.batchservice.job.RunTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** 회차 하나의 상태 전이. 서비스 호출은 가짜 [JobCaller], 저장소는 메모리. */
class BatchRunnerTest {
    private val store = InMemoryBatchJobStore()
    private val clock = Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"), ZoneOffset.UTC)
    private val modu = ModuProperties(services = mapOf("commerce-service" to "http://commerce:8200/"), internalApi = ModuProperties.InternalApi("tok"))
    private val calls = mutableListOf<Triple<String, String, Duration>>()
    private var reply: JobCallResult = JobCallResult(200, """{"job":"point-outbox-relay","outcome":"ok","durationMs":21,"requestId":"x","result":{"relayed":2}}""")
    private val caller = JobCaller { baseUrl, jobName, runId, timeout ->
        calls += Triple(baseUrl, jobName, timeout)
        assertTrue(runId.startsWith("job-"))
        reply
    }
    private val runner = BatchRunner(store, caller, modu, BatchProperties(), clock)
    private val job = store.saveJob(BatchJob(null, "commerce-service", "point-outbox-relay", cron = "0 * * * * *", enabled = true, timeoutSeconds = 120))

    @Test
    fun `2xx → SUCCEEDED with the service body as result`() {
        val run = runner.run(job, RunTrigger.CRON, null)

        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals(200, run.httpStatus)
        assertEquals(mapOf("relayed" to 2), (run.result!!["result"] as Map<*, *>))
        assertEquals("ok", run.result!!["outcome"])
        assertTrue(run.id.startsWith("job-") && run.id.length == 20, run.id)
        assertEquals(run, store.run(run.id))
        assertEquals(listOf(Triple("http://commerce:8200", "point-outbox-relay", Duration.ofSeconds(120))), calls)
        assertNull(run.error)
    }

    @Test
    fun `5xx → FAILED with the error from the body, 404 → FAILED unknown job`() {
        reply = JobCallResult(500, """{"job":"point-outbox-relay","outcome":"failed","error":"java.lang.IllegalStateException: boom"}""")
        val failed = runner.run(job, RunTrigger.MANUAL, "임준섭")
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(500, failed.httpStatus)
        assertEquals("java.lang.IllegalStateException: boom", failed.error)
        assertEquals("임준섭", failed.triggeredBy)

        reply = JobCallResult(404, """{"message":"unknown job"}""")
        val unknown = runner.run(job, RunTrigger.CRON, null)
        assertEquals(RunStatus.FAILED, unknown.status)
        assertTrue(unknown.error!!.contains("unknown job"), unknown.error)
    }

    @Test
    fun `409 from the service → SKIPPED, timeout → TIMEOUT, connection failure → FAILED`() {
        reply = JobCallResult(409, """{"message":"job already running"}""")
        assertEquals(RunStatus.SKIPPED, runner.run(job, RunTrigger.CRON, null).status)

        reply = JobCallResult(null, null, timedOut = true)
        val timeout = runner.run(job, RunTrigger.CRON, null)
        assertEquals(RunStatus.TIMEOUT, timeout.status)
        assertTrue(timeout.error!!.contains("120"), timeout.error)

        reply = JobCallResult(null, null, error = "Connection refused")
        val down = runner.run(job, RunTrigger.CRON, null)
        assertEquals(RunStatus.FAILED, down.status)
        assertEquals("Connection refused", down.error)
    }

    @Test
    fun `a job that is still RUNNING is not called again — a SKIPPED row is left instead`() {
        store.startRunIfNoneRunning(BatchJobRun("job-0000000000000001", job.id!!, job.service, job.jobName, RunTrigger.CRON, null, RunStatus.RUNNING, clock.instant()))

        val skipped = runner.run(job, RunTrigger.CRON, null)

        assertEquals(RunStatus.SKIPPED, skipped.status)
        assertTrue(skipped.error!!.contains("RUNNING"), skipped.error)
        assertTrue(calls.isEmpty())
        assertEquals(2, store.runs(job.id, null, null, 10).size)
    }

    @Test
    fun `unknown service → FAILED without calling`() {
        val other = store.saveJob(BatchJob(null, "nope-service", "x", cron = "0 * * * * *"))
        val run = runner.run(other, RunTrigger.CRON, null)
        assertEquals(RunStatus.FAILED, run.status)
        assertTrue(run.error!!.contains("nope-service"), run.error)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `timeout falls back to the default when the job has none`() {
        val noTimeout = store.saveJob(BatchJob(null, "commerce-service", "push-campaigns", cron = "0 * * * * *", timeoutSeconds = 0))
        runner.run(noTimeout, RunTrigger.CRON, null)
        assertEquals(Duration.ofSeconds(600), calls.single().third)
    }
}
