package com.example.batchservice.job

import com.example.batchservice.config.BatchProperties
import com.example.batchservice.job.ro.BatchJobChangeRoRepository
import com.example.batchservice.job.ro.BatchJobRoRepository
import com.example.batchservice.job.ro.BatchJobRunRoRepository
import com.example.batchservice.job.rw.BatchJobChangeRwRepository
import com.example.batchservice.job.rw.BatchJobRunRwRepository
import com.example.batchservice.job.rw.BatchJobRwRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * 잡 정의·회차·변경 이력을 mysql-platform 의 `modu-platform.batch_job*` 에 둔다. 전부 보관하고 목록만 `batch.store.capacity`(기본 500)로 자른다.
 *
 * 읽기/쓰기 분리(RW = master `mysql-platform`, RO = replica `mysql-platform-replica`, 계정 platform_ro):
 *
 * | 메서드 | DB | 이유 |
 * |---|---|---|
 * | saveJob·deleteJob·job·existsJob·jobsForScheduling | master | 쓰기, 콘솔이 저장 직후 다시 읽는 단건, 스케줄러 기동·리컨사일(잡을 빠뜨리면 안 된다) |
 * | startRunIfNoneRunning·addRun·updateRun·run·runningRuns | master | 회차 쓰기, 수동 실행 뒤 폴링, 고아 회차 정리 — 복제 지연이 있으면 틀린다 |
 * | jobs·runs·lastRun·changes | replica | 콘솔 목록. 몇 초 늦어도 되고 master 부하를 덜어 준다 |
 */
@Component
class JpaBatchJobStore(
    private val jobsRw: BatchJobRwRepository,
    private val runsRw: BatchJobRunRwRepository,
    private val changesRw: BatchJobChangeRwRepository,
    private val jobsRo: BatchJobRoRepository,
    private val runsRo: BatchJobRunRoRepository,
    private val changesRo: BatchJobChangeRoRepository,
    private val objectMapper: ObjectMapper,
    properties: BatchProperties,
    @Qualifier("rwTransactionManager") rwTransactionManager: PlatformTransactionManager,
) : BatchJobStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val maxList = properties.store.capacity.coerceIn(1, BatchJobStore.MAX_LIST)

    /** [startRunIfNoneRunning] 의 INSERT 전용 — 제약 위반을 트랜잭션 **밖**에서 잡아야 해서 템플릿을 쓴다(안에서 잡으면 rollback-only 라 커밋 때 다시 터진다). */
    private val insertTx = TransactionTemplate(rwTransactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    // ---- 잡 정의(master) ----

    @Transactional
    override fun saveJob(job: BatchJob): BatchJob {
        val entity = job.id?.let { jobsRw.findById(it).orElse(null) } ?: BatchJobEntity()
        entity.service = job.service
        entity.jobName = job.jobName
        entity.description = job.description
        entity.cron = job.cron
        entity.zone = job.zone
        entity.enabled = job.enabled
        entity.timeoutSeconds = job.timeoutSeconds
        return toJob(jobsRw.saveAndFlush(entity))
    }

    @Transactional(readOnly = true)
    override fun job(id: Long): BatchJob? = jobsRw.findById(id).orElse(null)?.let(::toJob)

    @Transactional(readOnly = true)
    override fun existsJob(service: String, jobName: String): Boolean = jobsRw.existsByServiceAndJobName(service, jobName)

    @Transactional(readOnly = true)
    override fun jobsForScheduling(): List<BatchJob> = jobsRw.findAllByOrderByServiceAscJobNameAsc().map(::toJob)

    @Transactional
    override fun deleteJob(id: Long) {
        jobsRw.deleteById(id)
    }

    // ---- 변경 이력 ----

    @Transactional
    override fun addChange(change: BatchJobChange) {
        changesRw.save(
            BatchJobChangeEntity(
                batchJobId = change.batchJobId, changedBy = change.changedBy, changedAt = change.changedAt,
                field = change.field, beforeValue = change.before, afterValue = change.after,
            ),
        )
    }

    @Transactional(transactionManager = RO_TX, readOnly = true)
    override fun changes(jobId: Long, limit: Int): List<BatchJobChange> =
        changesRo.findByBatchJobIdOrderByChangedAtDescIdDesc(jobId, PageRequest.of(0, limit.coerceIn(1, maxList))).map {
            BatchJobChange(it.id, it.batchJobId, it.changedBy, it.changedAt, it.field, it.beforeValue, it.afterValue)
        }

    // ---- 회차(master) ----

    /**
     * 1. master 에서 그 잡의 RUNNING 을 한 번 읽어 본다 — 있으면 바로 false(제약까지 가지 않는다).
     * 2. 없으면 자기 트랜잭션에서 `saveAndFlush`. 그 사이 다른 파드가 먼저 넣었으면 DB 가 유니크 키 `uk_batch_job_run_running` 으로 거절한다.
     * 3. 거절([DataIntegrityViolationException])은 트랜잭션 밖에서 잡아 false. 그 밖의 예외(DB 장애)는 그대로 올려 보낸다.
     */
    override fun startRunIfNoneRunning(run: BatchJobRun): Boolean {
        require(run.status == RunStatus.RUNNING) { "시작 회차는 RUNNING 이어야 한다: ${run.status}" }
        if (runsRw.existsByBatchJobIdAndStatus(run.batchJobId, RunStatus.RUNNING.name)) return false
        return try {
            insertTx.executeWithoutResult { runsRw.saveAndFlush(toEntity(run)) }
            true
        } catch (e: DataIntegrityViolationException) {
            log.info("run {} rejected: job {} already has a RUNNING run ({})", run.id, run.batchJobId, e.javaClass.simpleName)
            false
        }
    }

    @Transactional
    override fun addRun(run: BatchJobRun) {
        runsRw.save(toEntity(run))
    }

    @Transactional
    override fun updateRun(id: String, update: (BatchJobRun) -> BatchJobRun): BatchJobRun? {
        val entity = runsRw.findForUpdate(id) ?: return null
        val next = update(toRun(entity))
        require(next.id == id) { "회차 id 는 바꿀 수 없습니다: $id → ${next.id}" }
        apply(entity, next)
        runsRw.save(entity)
        return next
    }

    @Transactional(readOnly = true)
    override fun run(id: String): BatchJobRun? = runsRw.findById(id).orElse(null)?.let(::toRun)

    @Transactional(readOnly = true)
    override fun runningRuns(): List<BatchJobRun> = runsRw.findByStatus(RunStatus.RUNNING.name).map(::toRun)

    // ---- 목록(replica) ----

    @Transactional(transactionManager = RO_TX, readOnly = true)
    override fun jobs(): List<BatchJob> = jobsRo.findAllByOrderByServiceAscJobNameAsc().map(::toJob)

    @Transactional(transactionManager = RO_TX, readOnly = true)
    override fun runs(jobId: Long?, service: String?, status: RunStatus?, limit: Int): List<BatchJobRun> {
        var spec: Specification<BatchJobRunEntity> = Specification.unrestricted()
        if (jobId != null) spec = spec.and { r, _, cb -> cb.equal(r.get<Long>("batchJobId"), jobId) }
        if (service != null) spec = spec.and { r, _, cb -> cb.equal(r.get<String>("service"), service) }
        if (status != null) spec = spec.and { r, _, cb -> cb.equal(r.get<String>("status"), status.name) }
        val page = PageRequest.of(0, limit.coerceIn(1, maxList), Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("createdAt")))
        return runsRo.findAll(spec, page).content.map(::toRun)
    }

    /** idx_batch_job_run_job_started 를 타는 한 건 조회 — 회차 표가 커져도 비용이 일정하다. */
    @Transactional(transactionManager = RO_TX, readOnly = true)
    override fun lastRun(jobId: Long): BatchJobRun? = runsRo.findFirstByBatchJobIdOrderByStartedAtDescIdDesc(jobId)?.let(::toRun)

    companion object {
        const val RO_TX = "roTransactionManager"
    }

    // ---- 변환 ----

    private fun toJob(e: BatchJobEntity): BatchJob =
        BatchJob(e.id, e.service, e.jobName, e.description, e.cron, e.zone, e.enabled, e.timeoutSeconds, e.createdAt, e.updatedAt)

    private fun toEntity(r: BatchJobRun): BatchJobRunEntity = BatchJobRunEntity(id = r.id).also { apply(it, r) }

    private fun apply(e: BatchJobRunEntity, r: BatchJobRun) {
        e.batchJobId = r.batchJobId
        e.service = r.service
        e.jobName = r.jobName
        e.triggerType = r.trigger.name
        e.triggeredBy = r.triggeredBy
        e.status = r.status.name
        e.startedAt = r.startedAt
        e.finishedAt = r.finishedAt
        e.durationMs = r.durationMs
        e.httpStatus = r.httpStatus
        e.resultJson = r.result?.let { objectMapper.writeValueAsString(it) }
        e.error = r.error
    }

    private fun toRun(e: BatchJobRunEntity): BatchJobRun = BatchJobRun(
        id = e.id,
        batchJobId = e.batchJobId,
        service = e.service,
        jobName = e.jobName,
        trigger = RunTrigger.valueOf(e.triggerType),
        triggeredBy = e.triggeredBy,
        status = RunStatus.valueOf(e.status),
        startedAt = e.startedAt,
        finishedAt = e.finishedAt,
        durationMs = e.durationMs,
        httpStatus = e.httpStatus,
        result = e.resultJson?.let { objectMapper.readValue<Map<String, Any?>>(it) },
        error = e.error,
    )
}
