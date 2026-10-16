package com.example.batchservice.job

/**
 * 잡 정의·회차·변경 이력 저장소. 운영 구현은 [JpaBatchJobStore](mysql-platform), 단위 테스트는 [InMemoryBatchJobStore].
 * 레코드는 불변이라 밖으로 나간 스냅샷은 그대로 안전하다.
 */
interface BatchJobStore {

    companion object {
        /** 목록이 한 번에 돌려주는 최대 건수의 상한(batch.store.capacity 의 기본값). */
        const val MAX_LIST = 500
    }

    // ---- 잡 정의 ----
    /** id 가 null 이면 새로 넣고, 있으면 그 행을 덮어쓴다. 저장된 모양(id·시각 포함)을 돌려준다. */
    fun saveJob(job: BatchJob): BatchJob

    fun job(id: Long): BatchJob?

    fun jobs(): List<BatchJob>

    fun existsJob(service: String, jobName: String): Boolean

    fun deleteJob(id: Long)

    // ---- 변경 이력 ----
    fun addChange(change: BatchJobChange)

    fun changes(jobId: Long, limit: Int): List<BatchJobChange>

    // ---- 회차 ----
    /** RUNNING 회차를 넣는다. 그 잡에 RUNNING 이 이미 있으면(유니크 키) 넣지 않고 false. */
    fun startRunIfNoneRunning(run: BatchJobRun): Boolean

    /** 완결된 회차(SKIPPED 등)를 그대로 넣는다. */
    fun addRun(run: BatchJobRun)

    /** [id] 의 회차를 [update] 로 바꿔 쓰고 돌려준다. 없으면 null. */
    fun updateRun(id: String, update: (BatchJobRun) -> BatchJobRun): BatchJobRun?

    /** 단건(master — 수동 실행 뒤 폴링). */
    fun run(id: String): BatchJobRun?

    /** 최신순(startedAt 내림차순). 필터는 모두 선택. */
    fun runs(jobId: Long?, service: String?, status: RunStatus?, limit: Int): List<BatchJobRun>

    /** 잡 id → 마지막 회차. */
    fun lastRuns(): Map<Long, BatchJobRun>

    /** RUNNING 회차 전부(master — 재시작 복구). */
    fun runningRuns(): List<BatchJobRun>
}

/** 메모리 저장소. 러너·스케줄러 단위 테스트용(운영은 DB). 모든 접근은 한 락으로 직렬화한다. */
class InMemoryBatchJobStore : BatchJobStore {
    private val lock = Any()
    private val jobs = LinkedHashMap<Long, BatchJob>()
    private val runs = LinkedHashMap<String, BatchJobRun>()
    private val changes = mutableListOf<BatchJobChange>()
    private var nextJobId = 1L
    private var nextChangeId = 1L

    override fun saveJob(job: BatchJob): BatchJob = synchronized(lock) {
        val now = java.time.Instant.now()
        val saved = if (job.id == null) job.copy(id = nextJobId++, createdAt = now, updatedAt = now) else job.copy(updatedAt = now)
        jobs[saved.id!!] = saved
        saved
    }

    override fun job(id: Long): BatchJob? = synchronized(lock) { jobs[id] }

    override fun jobs(): List<BatchJob> = synchronized(lock) { jobs.values.sortedWith(compareBy({ it.service }, { it.jobName })) }

    override fun existsJob(service: String, jobName: String): Boolean = synchronized(lock) { jobs.values.any { it.service == service && it.jobName == jobName } }

    override fun deleteJob(id: Long) {
        synchronized(lock) { jobs.remove(id) }
    }

    override fun addChange(change: BatchJobChange) {
        synchronized(lock) { changes += change.copy(id = nextChangeId++) }
    }

    override fun changes(jobId: Long, limit: Int): List<BatchJobChange> = synchronized(lock) {
        changes.filter { it.batchJobId == jobId }.sortedWith(compareByDescending<BatchJobChange> { it.changedAt }.thenByDescending { it.id }).take(limit)
    }

    override fun startRunIfNoneRunning(run: BatchJobRun): Boolean = synchronized(lock) {
        if (runs.values.any { it.batchJobId == run.batchJobId && it.status == RunStatus.RUNNING }) return false
        runs[run.id] = run
        true
    }

    override fun addRun(run: BatchJobRun) {
        synchronized(lock) { runs[run.id] = run }
    }

    override fun updateRun(id: String, update: (BatchJobRun) -> BatchJobRun): BatchJobRun? = synchronized(lock) {
        val current = runs[id] ?: return null
        update(current).also { runs[id] = it }
    }

    override fun run(id: String): BatchJobRun? = synchronized(lock) { runs[id] }

    override fun runs(jobId: Long?, service: String?, status: RunStatus?, limit: Int): List<BatchJobRun> = synchronized(lock) {
        runs.values.asSequence()
            .filter { jobId == null || it.batchJobId == jobId }
            .filter { service == null || it.service == service }
            .filter { status == null || it.status == status }
            .sortedByDescending { it.startedAt }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    override fun lastRuns(): Map<Long, BatchJobRun> = synchronized(lock) {
        runs.values.groupBy { it.batchJobId }.mapValues { (_, list) -> list.maxBy { it.startedAt } }
    }

    override fun runningRuns(): List<BatchJobRun> = synchronized(lock) { runs.values.filter { it.status == RunStatus.RUNNING } }
}
