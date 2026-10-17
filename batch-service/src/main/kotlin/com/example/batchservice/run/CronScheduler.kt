package com.example.batchservice.run

import com.example.batchservice.config.BatchProperties
import com.example.batchservice.job.BatchJob
import com.example.batchservice.job.BatchJobRun
import com.example.batchservice.job.BatchJobStore
import com.example.batchservice.job.RunStatus
import com.example.batchservice.job.RunTrigger
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.support.CronTrigger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

/**
 * DB 의 `batch_job` 으로 cron 트리거를 동적으로 건다(schedule-service 방식). 기동 때 enabled 잡을 전부 걸고([run]),
 * 콘솔이 잡을 만들거나 주기·사용 여부를 바꾸면 [register]·[refresh]·[unregister] 로 즉시 다시 건다 — 재시작이 필요 없다.
 *
 * 걸린 상태는 파드 메모리에만 있다. 그래서 (1) 기동·대조는 replica 가 아니라 master 에서 읽고([BatchJobStore.jobsForScheduling]),
 * (2) [RECONCILE_INTERVAL] 마다 DB 와 대조해([reconcile]) 다른 파드가 바꾼 잡·기동 때 놓친 잡·지워진 잡을 맞춘다. 파드가 둘이어도 콘솔의 PATCH 가 한 파드에만 닿는 문제가 이걸로 풀린다.
 *
 * 파드마다 전부 걸기 때문에 파드가 2개면 같은 회차가 두 번 울린다. 그래서 울림 본문([fire])은 ShedLock(`batch:job-<id>`) 아래에서 돌려
 * 먼저 잡은 파드만 서비스를 부른다. 잠그는 시간은 유효 타임아웃 + 1분(본문이 끝나기 전에 다른 파드가 가져가지 않게), 최소 30초 — 그래서 30초보다 짧은 주기는 건너뛴다.
 * 수동 실행([runNow])은 락을 거치지 않는다 — 잡당 RUNNING 하나는 DB 유니크 키가 지킨다.
 */
@Order(20)
class CronScheduler(
    private val store: BatchJobStore,
    private val runner: BatchRunner,
    private val lock: LockingTaskExecutor,
    private val pool: ThreadPoolTaskScheduler,
    private val clock: Clock,
    private val properties: BatchProperties = BatchProperties(),
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 걸린 트리거와 그때의 주기 — 대조 때 "바뀌었는가" 를 이걸로 본다. */
    private class Entry(val future: ScheduledFuture<*>, val cron: String, val zone: String)

    private val scheduled = ConcurrentHashMap<Long, Entry>()
    private var reconcileTask: ScheduledFuture<*>? = null

    /** 대조 결과(새로 건·다시 건·뺀 잡 수). */
    data class Reconciled(val added: Int, val updated: Int, val removed: Int)

    override fun run(args: ApplicationArguments) {
        val jobs = store.jobsForScheduling()
        jobs.forEach(::register)
        reconcileTask = pool.scheduleAtFixedRate({ runCatching { reconcile() }.onFailure { log.error("batch job reconcile failed", it) } }, RECONCILE_INTERVAL)
        log.info("batch scheduler started: {} job(s), {} enabled, reconcile every {}", jobs.size, scheduled.size, RECONCILE_INTERVAL)
    }

    /** enabled 잡만 건다. 이미 걸려 있으면 먼저 뺀다(주기가 바뀌었을 수 있다). */
    @Synchronized
    fun register(job: BatchJob) {
        val id = requireNotNull(job.id)
        unregister(id)
        if (!job.enabled) return
        if (Crons.validate(job.cron, job.zone) != null) {
            log.warn("batch job {} ({}/{}) has an invalid cron '{}' — not scheduled", id, job.service, job.jobName, job.cron)
            return
        }
        val future = pool.schedule({ fire(id) }, CronTrigger(job.cron, ZoneId.of(job.zone)))
        if (future != null) scheduled[id] = Entry(future, job.cron, job.zone)
        log.info("batch job {} ({}/{}) scheduled: {} {}", id, job.service, job.jobName, job.cron, job.zone)
    }

    fun refresh(job: BatchJob) = register(job)

    @Synchronized
    fun unregister(id: Long) {
        scheduled.remove(id)?.future?.cancel(false)
    }

    fun registered(): Set<Long> = scheduled.keys.toSet()

    fun reconcileScheduled(): Boolean = reconcileTask?.let { !it.isCancelled } ?: false

    /** master 의 잡 정의와 걸린 트리거를 맞춘다: 켜져 있는데 안 걸렸거나 주기가 다르면 (다시) 걸고, 꺼졌거나 지워졌는데 걸려 있으면 뺀다. */
    @Synchronized
    fun reconcile(): Reconciled {
        val jobs = store.jobsForScheduling()
        var added = 0
        var updated = 0
        var removed = 0
        for (job in jobs) {
            val id = requireNotNull(job.id)
            val entry = scheduled[id]
            when {
                job.enabled && entry == null -> { register(job); if (scheduled.containsKey(id)) added++ }
                job.enabled && (entry!!.cron != job.cron || entry.zone != job.zone) -> { register(job); updated++ }
                !job.enabled && entry != null -> { unregister(id); removed++ }
            }
        }
        val alive = jobs.mapNotNull { it.id }.toSet()
        for (id in scheduled.keys.filter { it !in alive }) { unregister(id); removed++ }
        if (added + updated + removed > 0) log.info("batch jobs reconciled: {} added, {} updated, {} removed", added, updated, removed)
        return Reconciled(added, updated, removed)
    }

    /** 다음 실행 시각(걸려 있지 않거나 꺼져 있으면 null). */
    fun nextRunAt(job: BatchJob): Instant? =
        if (!job.enabled) null else Crons.next(job.cron, job.zone, 1, clock.instant()).firstOrNull()

    /** cron 이 울렸다. 잡을 DB 에서 다시 읽어(그 사이 바뀌었거나 꺼졌거나 지워졌을 수 있다) 잡 락 아래에서 한 회차를 돌린다. */
    fun fire(jobId: Long) {
        val job = store.job(jobId)
        if (job == null || !job.enabled) {
            log.info("batch job {} fired but is {} — unscheduling", jobId, if (job == null) "gone" else "disabled")
            unregister(jobId)
            return
        }
        val atMost = Duration.ofSeconds(BatchRunner.effectiveTimeoutSeconds(job, properties.run).toLong()).plus(LOCK_MARGIN)
        lock.executeWithLock(
            Runnable {
                try {
                    runner.run(job, RunTrigger.CRON, null)
                } catch (e: Exception) {
                    log.error("batch job {} ({}/{}) tick failed unexpectedly", jobId, job.service, job.jobName, e)
                }
            },
            LockConfiguration(clock.instant(), lockName(jobId), atMost, LOCK_AT_LEAST),
        )
    }

    /** 콘솔의 "지금 실행". RUNNING 행을 바로 만들어 돌려주고 호출은 풀에서 이어 간다(콘솔은 id 로 폴링). 이미 돌고 있으면 SKIPPED 행. */
    fun runNow(job: BatchJob, by: String?): BatchJobRun {
        val started = runner.start(job, RunTrigger.MANUAL, by)
        if (started.status == RunStatus.RUNNING) pool.execute { runner.complete(started, job) }
        return started
    }

    companion object {
        const val LOCK_NAME_PREFIX = "batch:job-"
        val LOCK_MARGIN: Duration = Duration.ofMinutes(1)
        val LOCK_AT_LEAST: Duration = Duration.ofSeconds(30)
        val RECONCILE_INTERVAL: Duration = Duration.ofMinutes(1)

        fun lockName(jobId: Long): String = LOCK_NAME_PREFIX + jobId
    }
}
