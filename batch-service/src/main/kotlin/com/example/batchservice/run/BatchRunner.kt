package com.example.batchservice.run

import com.example.batchservice.config.BatchProperties
import com.example.batchservice.config.ModuProperties
import com.example.batchservice.job.BatchJob
import com.example.batchservice.job.BatchJobRun
import com.example.batchservice.job.BatchJobStore
import com.example.batchservice.job.RunStatus
import com.example.batchservice.job.RunTrigger
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * 회차 하나를 끝까지 돌린다: RUNNING 행을 열고([BatchJobStore.startRunIfNoneRunning]) → 서비스를 부르고([JobCaller]) → 결과로 닫는다.
 *
 * | 서비스 응답 | 회차 상태 | error |
 * |---|---|---|
 * | 2xx | SUCCEEDED | — (result = 응답 JSON) |
 * | 409 | SKIPPED | 서비스가 이미 돌리고 있다 |
 * | 404 | FAILED | 서비스에 그 잡이 없다 |
 * | 그 밖의 4xx·5xx | FAILED | 응답 본문의 error(없으면 본문) |
 * | 읽기 타임아웃 | TIMEOUT | N초 안에 답하지 않았다 |
 * | 연결 실패 | FAILED | 원인 메시지 |
 *
 * 그 잡에 RUNNING 회차가 이미 있으면 서비스를 부르지 않고 SKIPPED 행만 남긴다(cron 이 앞 회차보다 빨리 울렸다).
 * 회차 id 는 `job-` + 16자 — 서비스에 `X-Request-Id` 로 보내므로 OpenSearch 에서 양쪽 로그가 한 id 로 묶인다. 끝나면 `batch.run` 이벤트 한 줄.
 */
class BatchRunner(
    private val store: BatchJobStore,
    private val caller: JobCaller,
    private val modu: ModuProperties,
    private val properties: BatchProperties,
    private val clock: Clock,
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val eventLog = LoggerFactory.getLogger(EVENT)

    /** RUNNING 행을 먼저 만들어 돌려준다(콘솔이 id 로 폴링). 못 열면(이미 RUNNING) SKIPPED 행. 그 뒤 [complete] 를 부른다. */
    fun start(job: BatchJob, trigger: RunTrigger, triggeredBy: String?): BatchJobRun {
        val now = clock.instant()
        val run = BatchJobRun(newRunId(), requireNotNull(job.id), job.service, job.jobName, trigger, triggeredBy, RunStatus.RUNNING, now)
        if (store.startRunIfNoneRunning(run)) return run
        val skipped = run.copy(status = RunStatus.SKIPPED, finishedAt = now, durationMs = 0, error = "앞 회차가 아직 RUNNING 이라 건너뛰었다")
        store.addRun(skipped)
        event(skipped)
        return skipped
    }

    /** [start] 가 열어 둔 RUNNING 회차를 서비스를 불러 닫는다. SKIPPED 로 받은 회차는 그대로 돌려준다. */
    fun complete(run: BatchJobRun, job: BatchJob): BatchJobRun {
        if (run.status != RunStatus.RUNNING) return run
        MDC.put(MDC_REQUEST_ID, run.id)
        MDC.put(MDC_JOB, "${job.service}/${job.jobName}")
        val started = System.nanoTime()
        try {
            val outcome = call(job, run.id)
            val finished = clock.instant()
            val durationMs = (System.nanoTime() - started) / 1_000_000
            val closed = store.updateRun(run.id) {
                it.copy(status = outcome.status, finishedAt = finished, durationMs = durationMs, httpStatus = outcome.httpStatus, result = outcome.result, error = outcome.error)
            } ?: run.copy(status = outcome.status, finishedAt = finished, durationMs = durationMs, httpStatus = outcome.httpStatus, result = outcome.result, error = outcome.error)
            event(closed)
            return closed
        } finally {
            MDC.remove(MDC_JOB)
            MDC.remove(MDC_REQUEST_ID)
        }
    }

    fun run(job: BatchJob, trigger: RunTrigger, triggeredBy: String?): BatchJobRun = complete(start(job, trigger, triggeredBy), job)

    private data class Outcome(val status: RunStatus, val httpStatus: Int? = null, val result: Map<String, Any?>? = null, val error: String? = null)

    private fun call(job: BatchJob, runId: String): Outcome {
        val baseUrl = modu.serviceUrl(job.service) ?: return Outcome(RunStatus.FAILED, error = "modu.services 에 없는 서비스: ${job.service}")
        val timeout = Duration.ofSeconds(timeoutSeconds(job).toLong())
        val r = caller.call(baseUrl, job.jobName, runId, timeout)
        if (r.timedOut) return Outcome(RunStatus.TIMEOUT, error = "${timeout.seconds}초 안에 응답하지 않았다")
        if (r.status == null) return Outcome(RunStatus.FAILED, error = r.error ?: "서비스를 부르지 못했다")
        val body = r.body?.let { parse(it) }
        return when {
            r.status in 200..299 -> Outcome(RunStatus.SUCCEEDED, r.status, result = body)
            r.status == 409 -> Outcome(RunStatus.SKIPPED, r.status, error = "서비스가 이미 그 잡을 돌리고 있다")
            r.status == 404 -> Outcome(RunStatus.FAILED, r.status, error = "서비스에 그 잡이 없다(unknown job): ${job.jobName}")
            else -> Outcome(RunStatus.FAILED, r.status, result = body, error = errorOf(body, r.body, r.status))
        }
    }

    private fun timeoutSeconds(job: BatchJob): Int =
        (if (job.timeoutSeconds > 0) job.timeoutSeconds else properties.run.defaultTimeoutSeconds).coerceAtMost(properties.run.maxTimeoutSeconds)

    private fun parse(body: String): Map<String, Any?>? =
        runCatching { objectMapper.readValue<Map<String, Any?>>(body) }.getOrNull()

    private fun errorOf(body: Map<String, Any?>?, raw: String?, status: Int): String =
        (body?.get("error") as? String) ?: (body?.get("message") as? String) ?: raw?.take(500) ?: "HTTP $status"

    private fun event(run: BatchJobRun) {
        val args = arrayOf(
            kv("event", EVENT), kv("runId", run.id), kv("service", run.service), kv("job", run.jobName), kv("trigger", run.trigger.name),
            kv("status", run.status.name), kv("durationMs", run.durationMs), kv("httpStatus", run.httpStatus), kv("error", run.error),
        )
        if (run.status == RunStatus.SUCCEEDED || run.status == RunStatus.SKIPPED) {
            eventLog.info("batch run {} {}/{} {} {}ms", run.id, run.service, run.jobName, run.status, run.durationMs, *args)
        } else {
            eventLog.error("batch run {} {}/{} {} {}ms: {}", run.id, run.service, run.jobName, run.status, run.durationMs, run.error, *args)
        }
    }

    companion object {
        const val EVENT = "batch.run"
        const val MDC_REQUEST_ID = "requestId"
        const val MDC_JOB = "job"
        const val RUN_ID_PREFIX = "job-"

        fun newRunId(): String = RUN_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
