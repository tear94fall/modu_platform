package com.example.batchservice.job

import com.example.batchservice.api.ApiException
import com.example.batchservice.api.CreateJobRequest
import com.example.batchservice.api.UpdateJobRequest
import com.example.batchservice.config.BatchProperties
import com.example.batchservice.config.ModuProperties
import com.example.batchservice.gateway.ServiceJobsGateway
import com.example.batchservice.run.CronScheduler
import com.example.batchservice.run.Crons
import java.time.Clock
import java.time.Instant

/** 콘솔 잡 목록의 한 줄 — 정의 + 다음 실행 시각 + 마지막 회차. */
data class BatchJobView(
    val id: Long,
    val service: String,
    val jobName: String,
    val description: String?,
    val cron: String,
    val zone: String,
    val enabled: Boolean,
    val timeoutSeconds: Int,
    val nextRunAt: Instant?,
    val lastRun: BatchJobRun?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
) {
    companion object {
        fun of(job: BatchJob, nextRunAt: Instant?, lastRun: BatchJobRun?) = BatchJobView(
            requireNotNull(job.id), job.service, job.jobName, job.description, job.cron, job.zone, job.enabled, job.timeoutSeconds,
            nextRunAt, lastRun, job.createdAt, job.updatedAt,
        )
    }
}

data class ServiceView(val name: String, val url: String)

/** 서비스가 알려 준 잡(`GET /api-internal/v1/jobs` 의 한 항목). */
data class ServiceJobView(val name: String, val description: String? = null)

/**
 * 콘솔 "배치" 탭의 쓰임새: 잡 CRUD(검증·변경 이력·트리거 즉시 갱신), 수동 실행, 실행 이력, cron 미리보기, 서비스와 그 서비스의 잡 목록.
 * 잘못된 입력은 [ApiException] 400/404/409 로.
 */
class BatchService(
    private val store: BatchJobStore,
    private val scheduler: CronScheduler,
    private val modu: ModuProperties,
    private val properties: BatchProperties,
    private val serviceJobs: ServiceJobsGateway,
    private val clock: Clock,
) {
    fun jobs(): List<BatchJobView> {
        val last = store.lastRuns()
        return store.jobs().map { BatchJobView.of(it, scheduler.nextRunAt(it), last[it.id]) }
    }

    fun job(id: Long): BatchJobView = view(find(id))

    fun create(req: CreateJobRequest, by: String): BatchJobView {
        val service = req.service?.trim().orEmpty()
        val jobName = req.jobName?.trim().orEmpty()
        if (modu.serviceUrl(service) == null) throw ApiException.badRequest("unknown_service", "modu.services 에 없는 서비스입니다: $service")
        if (jobName.isBlank() || !JOB_NAME.matches(jobName)) throw ApiException.badRequest("invalid_job_name", "잡 이름은 영문 소문자·숫자·하이픈 1~64자입니다.")
        val cron = req.cron?.trim().orEmpty()
        val zone = req.zone?.trim()?.takeIf { it.isNotEmpty() } ?: BatchJob.DEFAULT_ZONE
        Crons.validate(cron, zone)?.let { throw ApiException.badRequest("invalid_cron", it) }
        val timeout = timeout(req.timeoutSeconds)
        if (store.existsJob(service, jobName)) throw ApiException.conflict("job_exists", "이미 등록된 잡입니다: $service/$jobName")
        val saved = store.saveJob(
            BatchJob(null, service, jobName, req.description?.trim()?.takeIf { it.isNotEmpty() }, cron, zone, req.enabled ?: false, timeout),
        )
        store.addChange(BatchJobChange(null, saved.id!!, by, clock.instant(), "created", null, "$cron $zone enabled=${saved.enabled} timeout=${timeout}s"))
        scheduler.register(saved)
        return view(saved)
    }

    fun update(id: Long, req: UpdateJobRequest, by: String): BatchJobView {
        val before = find(id)
        val next = before.copy(
            cron = req.cron?.trim()?.takeIf { it.isNotEmpty() } ?: before.cron,
            zone = req.zone?.trim()?.takeIf { it.isNotEmpty() } ?: before.zone,
            enabled = req.enabled ?: before.enabled,
            timeoutSeconds = req.timeoutSeconds?.let { timeout(it) } ?: before.timeoutSeconds,
            description = if (req.description != null) req.description.trim().takeIf { it.isNotEmpty() } else before.description,
        )
        Crons.validate(next.cron, next.zone)?.let { throw ApiException.badRequest("invalid_cron", it) }
        val saved = store.saveJob(next)
        val at = clock.instant()
        changed("cron", before.cron, saved.cron, id, by, at)
        changed("zone", before.zone, saved.zone, id, by, at)
        changed("enabled", before.enabled.toString(), saved.enabled.toString(), id, by, at)
        changed("timeoutSeconds", before.timeoutSeconds.toString(), saved.timeoutSeconds.toString(), id, by, at)
        changed("description", before.description, saved.description, id, by, at)
        scheduler.refresh(saved)
        return view(saved)
    }

    fun delete(id: Long, by: String) {
        val job = find(id)
        scheduler.unregister(id)
        store.deleteJob(id)
        store.addChange(BatchJobChange(null, id, by, clock.instant(), "deleted", "${job.service}/${job.jobName}", null))
    }

    fun runNow(id: Long, by: String): BatchJobRun = scheduler.runNow(find(id), by)

    fun runs(jobId: Long?, service: String?, status: RunStatus?, limit: Int): List<BatchJobRun> =
        store.runs(jobId, service, status, limit.coerceIn(1, properties.store.capacity))

    fun run(runId: String): BatchJobRun = store.run(runId) ?: throw ApiException.notFound("run_not_found", "없는 회차입니다: $runId")

    fun changes(jobId: Long, limit: Int): List<BatchJobChange> = store.changes(jobId, limit.coerceIn(1, properties.store.capacity))

    fun cronPreview(cron: String, zone: String?, count: Int): List<Instant> {
        val z = zone?.trim()?.takeIf { it.isNotEmpty() } ?: BatchJob.DEFAULT_ZONE
        Crons.validate(cron, z)?.let { throw ApiException.badRequest("invalid_cron", it) }
        return Crons.next(cron, z, count.coerceIn(1, 20), clock.instant())
    }

    fun services(): List<ServiceView> = modu.services.toSortedMap().map { (name, url) -> ServiceView(name, url) }

    fun serviceJobs(service: String): List<ServiceJobView> {
        val url = modu.serviceUrl(service) ?: throw ApiException.notFound("unknown_service", "modu.services 에 없는 서비스입니다: $service")
        return serviceJobs.jobs(service, url)
    }

    private fun find(id: Long): BatchJob = store.job(id) ?: throw ApiException.notFound("job_not_found", "없는 잡입니다: $id")

    private fun view(job: BatchJob): BatchJobView = BatchJobView.of(job, scheduler.nextRunAt(job), store.lastRuns()[job.id])

    private fun timeout(seconds: Int?): Int {
        val t = seconds ?: properties.run.defaultTimeoutSeconds
        if (t < 1 || t > properties.run.maxTimeoutSeconds) {
            throw ApiException.badRequest("invalid_timeout", "타임아웃은 1~${properties.run.maxTimeoutSeconds}초입니다.")
        }
        return t
    }

    private fun changed(field: String, before: String?, after: String?, id: Long, by: String, at: Instant) {
        if (before != after) store.addChange(BatchJobChange(null, id, by, at, field, before, after))
    }

    companion object {
        private val JOB_NAME = Regex("[a-z0-9][a-z0-9-]{0,63}")
    }
}
