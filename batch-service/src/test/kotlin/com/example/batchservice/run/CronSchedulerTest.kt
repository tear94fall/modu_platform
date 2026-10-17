package com.example.batchservice.run

import com.example.batchservice.config.BatchProperties
import com.example.batchservice.config.ModuProperties
import com.example.batchservice.job.BatchJob
import com.example.batchservice.job.InMemoryBatchJobStore
import com.example.batchservice.job.RunStatus
import com.example.batchservice.job.RunTrigger
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import net.javacrumbs.shedlock.core.SimpleLock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** DB 의 잡으로 cron 트리거를 걸고 빼는 것, 회차가 울렸을 때 ShedLock 아래에서 러너가 도는 것, 수동 실행. 실제 울림은 기다리지 않는다. */
class CronSchedulerTest {
    private val store = InMemoryBatchJobStore()
    private val clock = Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"), ZoneOffset.UTC)
    private val modu = ModuProperties(services = mapOf("commerce-service" to "http://commerce:8200"))
    private val calls = mutableListOf<String>()
    private val caller = JobCaller { _, jobName, _, _ -> calls += jobName; JobCallResult(200, """{"outcome":"ok"}""") }
    private val runner = BatchRunner(store, caller, modu, BatchProperties(), clock)
    private val locks = mutableListOf<LockConfiguration>()
    /** 락은 늘 잡히는 가짜 — 어떤 이름·기간으로 잠그려 했는지만 기록한다. */
    private val lock: LockingTaskExecutor = DefaultLockingTaskExecutor(LockProvider { config -> locks += config; Optional.of(SimpleLock { }) })
    private val pool = ThreadPoolTaskScheduler().apply { setPoolSize(2); initialize() }
    private val scheduler = CronScheduler(store, runner, lock, pool, clock)

    @AfterEach
    fun shutdown() = pool.shutdown()

    private fun job(name: String, enabled: Boolean = true, cron: String = "0 0 4 * * *") =
        store.saveJob(BatchJob(null, "commerce-service", name, cron = cron, enabled = enabled, timeoutSeconds = 60))

    @Test
    fun `registers enabled jobs on startup, skips disabled ones`() {
        val on = job("a-on")
        job("b-off", enabled = false)

        scheduler.run(DefaultApplicationArguments())

        assertEquals(setOf(on.id), scheduler.registered())
        assertEquals(Instant.parse("2026-10-17T19:00:00Z"), scheduler.nextRunAt(on))
        assertNull(scheduler.nextRunAt(store.job(on.id!!)!!.copy(enabled = false)))
    }

    @Test
    fun `refresh re-registers with the new cron or removes when disabled, unregister removes`() {
        val j = job("a")
        scheduler.register(j)
        assertEquals(setOf(j.id), scheduler.registered())

        scheduler.refresh(store.saveJob(j.copy(cron = "0 0 5 * * *")))
        assertEquals(setOf(j.id), scheduler.registered())
        assertEquals(Instant.parse("2026-10-17T20:00:00Z"), scheduler.nextRunAt(store.job(j.id!!)!!))

        scheduler.refresh(store.saveJob(store.job(j.id!!)!!.copy(enabled = false)))
        assertTrue(scheduler.registered().isEmpty())

        scheduler.register(store.saveJob(store.job(j.id!!)!!.copy(enabled = true)))
        scheduler.unregister(j.id!!)
        assertTrue(scheduler.registered().isEmpty())
    }

    /** 다른 파드가 바꾼 것(또는 기동 때 replica 가 늦었던 것)은 1분마다 master 와 맞춘다 — 새 잡은 걸고, 바뀐 cron 은 다시 걸고, 지워졌거나 꺼진 잡은 뺀다. */
    @Test
    fun `reconcile registers new jobs, re-registers changed crons and unregisters deleted or disabled ones`() {
        val keep = job("keep")
        val changing = job("changing")
        val going = job("going")
        scheduler.run(DefaultApplicationArguments())
        assertEquals(setOf(keep.id, changing.id, going.id), scheduler.registered())

        // 이 파드를 거치지 않고 DB 만 바뀜
        val added = job("added")
        store.saveJob(store.job(changing.id!!)!!.copy(cron = "0 0 6 * * *"))
        store.deleteJob(going.id!!)
        val disabledLater = job("off-later")
        store.saveJob(store.job(disabledLater.id!!)!!.copy(enabled = false))

        val result = scheduler.reconcile()

        assertEquals(setOf(keep.id, changing.id, added.id), scheduler.registered())
        assertEquals(Instant.parse("2026-10-17T21:00:00Z"), scheduler.nextRunAt(store.job(changing.id!!)!!), "바뀐 cron(06:00 KST)")
        assertEquals(1, result.added)
        assertEquals(1, result.updated)
        assertEquals(1, result.removed)
        assertEquals(CronScheduler.Reconciled(0, 0, 0), scheduler.reconcile(), "두 번째는 바뀐 게 없다")
    }

    @Test
    fun `startup schedules the periodic reconcile`() {
        scheduler.run(DefaultApplicationArguments())
        assertTrue(scheduler.reconcileScheduled())
    }

    @Test
    fun `a tick for a job that was disabled meanwhile does nothing and unschedules it`() {
        val j = job("off")
        scheduler.register(j)
        store.saveJob(j.copy(enabled = false))

        scheduler.fire(j.id!!)

        assertTrue(calls.isEmpty())
        assertTrue(scheduler.registered().isEmpty())
    }

    @Test
    fun `a cron tick runs the job under the job lock with a CRON trigger`() {
        val j = job("point-outbox-relay")

        scheduler.fire(j.id!!)

        assertEquals(listOf("point-outbox-relay"), calls)
        val lockConfig = locks.single()
        assertEquals("batch:job-${j.id}", lockConfig.name)
        assertTrue(lockConfig.lockAtMostUntil.isAfter(clock.instant().plusSeconds(60)), "타임아웃보다 길게 잠근다")
        val run = store.runs(j.id, null, null, 1).single()
        assertEquals(RunTrigger.CRON, run.trigger)
        assertEquals(RunStatus.SUCCEEDED, run.status)
    }

    @Test
    fun `a tick for a deleted job does nothing`() {
        val j = job("gone")
        store.deleteJob(j.id!!)
        scheduler.fire(j.id!!)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `runNow starts a MANUAL run right away and completes it in the background`() {
        val j = job("push-campaigns")
        val done = CountDownLatch(1)
        val slowCaller = JobCaller { _, _, _, _ -> done.await(5, TimeUnit.SECONDS); JobCallResult(200, """{"outcome":"ok"}""") }
        val slowScheduler = CronScheduler(store, BatchRunner(store, slowCaller, modu, BatchProperties(), clock), lock, pool, clock)

        val started = slowScheduler.runNow(j, "임준섭")

        assertEquals(RunStatus.RUNNING, started.status)
        assertEquals(RunTrigger.MANUAL, started.trigger)
        assertEquals("임준섭", started.triggeredBy)
        assertEquals(RunStatus.SKIPPED, slowScheduler.runNow(j, "임준섭").status, "돌고 있는 동안 또 누르면 SKIPPED")
        done.countDown()
        var waited = 0
        while (store.run(started.id)!!.status == RunStatus.RUNNING && waited++ < 50) Thread.sleep(100)
        assertEquals(RunStatus.SUCCEEDED, store.run(started.id)!!.status)
        assertNotNull(store.run(started.id)!!.finishedAt)
    }
}
