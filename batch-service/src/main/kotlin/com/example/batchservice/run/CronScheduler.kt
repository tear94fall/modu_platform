package com.example.batchservice.run

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
 * 파드마다 전부 걸기 때문에 파드가 2개면 같은 회차가 두 번 울린다. 그래서 울림 본문([fire])은 ShedLock(`batch:job-<id>`) 아래에서 돌려
 * 먼저 잡은 파드만 서비스를 부른다. 잠그는 시간은 잡 타임아웃 + 1분(본문이 끝나기 전에 다른 파드가 가져가지 않게), 최소 30초.
 * 수동 실행([runNow])은 락을 거치지 않는다 — 잡당 RUNNING 하나는 DB 유니크 키가 지킨다.
 */
@Order(20)
class CronScheduler(
    private val store: BatchJobStore,
    private val runner: BatchRunner,
    private val lock: LockingTaskExecutor,
    private val pool: ThreadPoolTaskScheduler,
    private val clock: Clock,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduled = ConcurrentHashMap<Long, ScheduledFuture<*>>()

    override fun run(args: ApplicationArguments) {
        val jobs = store.jobs()
        jobs.forEach(::register)
        log.info("batch scheduler started: {} job(s), {} enabled", jobs.size, scheduled.size)
    }

    /** enabled 잡만 건다. 이미 걸려 있으면 먼저 뺀다(주기가 바뀌었을 수 있다). */
    fun register(job: BatchJob) {
        val id = requireNotNull(job.id)
        unregister(id)
        if (!job.enabled) return
        if (Crons.validate(job.cron, job.zone) != null) {
            log.warn("batch job {} ({}/{}) has an invalid cron '{}' — not scheduled", id, job.service, job.jobName, job.cron)
            return
        }
        val future = pool.schedule({ fire(id) }, CronTrigger(job.cron, ZoneId.of(job.zone)))
        if (future != null) scheduled[id] = future
        log.info("batch job {} ({}/{}) scheduled: {} {}", id, job.service, job.jobName, job.cron, job.zone)
    }

    fun refresh(job: BatchJob) = register(job)

    fun unregister(id: Long) {
        scheduled.remove(id)?.cancel(false)
    }

    fun registered(): Set<Long> = scheduled.keys.toSet()

    /** 다음 실행 시각(걸려 있지 않거나 꺼져 있으면 null). */
    fun nextRunAt(job: BatchJob): Instant? =
        if (!job.enabled) null else Crons.next(job.cron, job.zone, 1, clock.instant()).firstOrNull()

    /** cron 이 울렸다. 잡을 DB 에서 다시 읽어(그 사이 바뀌었거나 지워졌을 수 있다) 잡 락 아래에서 한 회차를 돌린다. */
    fun fire(jobId: Long) {
        val job = store.job(jobId)
        if (job == null) {
            log.info("batch job {} fired but no longer exists — unscheduling", jobId)
            unregister(jobId)
            return
        }
        val atMost = Duration.ofSeconds(job.timeoutSeconds.coerceAtLeast(0).toLong()).plus(LOCK_MARGIN)
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

        fun lockName(jobId: Long): String = LOCK_NAME_PREFIX + jobId
    }
}
