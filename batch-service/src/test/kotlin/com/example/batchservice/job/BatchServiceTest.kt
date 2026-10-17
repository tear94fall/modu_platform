package com.example.batchservice.job

import com.example.batchservice.api.ApiException
import com.example.batchservice.api.CreateJobRequest
import com.example.batchservice.api.UpdateJobRequest
import com.example.batchservice.config.BatchProperties
import com.example.batchservice.config.ModuProperties
import com.example.batchservice.run.BatchRunner
import com.example.batchservice.run.CronScheduler
import com.example.batchservice.run.JobCallResult
import com.example.batchservice.run.JobCaller
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.SimpleLock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/** 입력 검증(400/404/409), 변경 이력, 트리거 갱신. 저장소는 메모리, 서비스 호출은 가짜. */
class BatchServiceTest {
    private val store = InMemoryBatchJobStore()
    private val clock = Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"), ZoneOffset.UTC)
    private val modu = ModuProperties(services = mapOf("commerce-service" to "http://commerce:8200", "member-service" to "http://member:8080"))
    private val props = BatchProperties()
    private val pool = ThreadPoolTaskScheduler().apply { setPoolSize(1); initialize() }
    private val runner = BatchRunner(store, JobCaller { _, _, _, _ -> JobCallResult(200, "{}") }, modu, props, clock)
    private val scheduler = CronScheduler(store, runner, DefaultLockingTaskExecutor(LockProvider { Optional.of(SimpleLock { }) }), pool, clock)
    private val service = BatchService(store, scheduler, modu, props, { _, _ -> listOf(ServiceJobView("point-outbox-relay", "아웃박스")) }, clock)

    @AfterEach
    fun shutdown() = pool.shutdown()

    private fun create(enabled: Boolean = true) =
        service.create(CreateJobRequest("commerce-service", "point-outbox-relay", "0 */2 * * * *", enabled = enabled, timeoutSeconds = 120, description = "아웃박스"), "임준섭")

    @Test
    fun `create validates service, job name, cron, timeout and duplicates`() {
        fun bad(req: CreateJobRequest, error: String, status: HttpStatus = HttpStatus.BAD_REQUEST) {
            val e = assertThrows(ApiException::class.java) { service.create(req, "x") }
            assertEquals(error, e.error)
            assertEquals(status, e.status)
        }
        bad(CreateJobRequest("nope", "a", "0 * * * * *"), "unknown_service")
        bad(CreateJobRequest("commerce-service", "Bad Name", "0 * * * * *"), "invalid_job_name")
        bad(CreateJobRequest("commerce-service", "a", "* * *"), "invalid_cron")
        bad(CreateJobRequest("commerce-service", "a", "0 * * * * *", zone = "Mars/Olympus"), "invalid_cron")
        bad(CreateJobRequest("commerce-service", "a", "0 * * * * *", timeoutSeconds = 0), "invalid_timeout")
        bad(CreateJobRequest("commerce-service", "a", "0 * * * * *", timeoutSeconds = 99999), "invalid_timeout")
        create()
        bad(CreateJobRequest("commerce-service", "point-outbox-relay", "0 * * * * *"), "job_exists", HttpStatus.CONFLICT)
        assertTrue(store.jobs().size == 1)
    }

    @Test
    fun `create stores defaults, records a created change and schedules enabled jobs`() {
        val v = create()

        assertEquals("Asia/Seoul", v.zone)
        assertEquals(Instant.parse("2026-10-17T03:02:00Z"), v.nextRunAt)
        assertEquals(setOf(v.id), scheduler.registered())
        assertEquals("created", store.changes(v.id, 10).single().field)

        val off = service.create(CreateJobRequest("commerce-service", "push-campaigns", "0 * * * * *"), "임준섭")
        assertFalse(off.enabled)
        assertEquals(600, off.timeoutSeconds)
        assertEquals(setOf(v.id), scheduler.registered(), "꺼진 잡은 걸지 않는다")
    }

    @Test
    fun `update changes only the given fields, records each changed field and re-registers`() {
        val v = create()

        val updated = service.update(v.id, UpdateJobRequest(cron = "0 0 4 * * *", enabled = false), "임준섭")

        assertEquals("0 0 4 * * *", updated.cron)
        assertFalse(updated.enabled)
        assertEquals(120, updated.timeoutSeconds)
        assertEquals("아웃박스", updated.description)
        assertTrue(scheduler.registered().isEmpty())
        val fields = store.changes(v.id, 10).map { it.field }.toSet()
        assertEquals(setOf("created", "cron", "enabled"), fields)

        assertEquals("invalid_cron", assertThrows(ApiException::class.java) { service.update(v.id, UpdateJobRequest(cron = "bad"), "x") }.error)
        assertEquals("job_not_found", assertThrows(ApiException::class.java) { service.update(999, UpdateJobRequest(), "x") }.error)
    }

    @Test
    fun `delete unschedules, removes the job, keeps runs and records deleted`() {
        val v = create()
        service.runNow(v.id, "임준섭")

        service.delete(v.id, "임준섭")

        assertTrue(scheduler.registered().isEmpty())
        assertEquals("job_not_found", assertThrows(ApiException::class.java) { service.job(v.id) }.error)
        assertEquals(1, store.runs(v.id, null, null, 10).size)
        assertEquals("deleted", store.changes(v.id, 10).first().field)
    }

    @Test
    fun `service jobs come from the gateway, unknown service is 404, cron preview validates`() {
        assertEquals("point-outbox-relay", service.serviceJobs("commerce-service").single().name)
        assertEquals("unknown_service", assertThrows(ApiException::class.java) { service.serviceJobs("nope") }.error)
        assertEquals(listOf("commerce-service", "member-service"), service.services().map { it.name })
        assertEquals(2, service.cronPreview("0 */2 * * * *", null, 2).size)
        assertEquals("invalid_cron", assertThrows(ApiException::class.java) { service.cronPreview("x", null, 2) }.error)
        assertEquals("run_not_found", assertThrows(ApiException::class.java) { service.run("job-none") }.error)
    }
}
