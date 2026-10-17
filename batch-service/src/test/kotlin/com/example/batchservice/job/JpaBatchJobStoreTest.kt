package com.example.batchservice.job

import com.example.batchservice.config.BatchPropertiesConfig
import com.example.batchservice.config.RoJpaConfig
import com.example.batchservice.config.RwJpaConfig
import com.example.batchservice.job.rw.BatchJobChangeRwRepository
import com.example.batchservice.job.rw.BatchJobRunRwRepository
import com.example.batchservice.job.rw.BatchJobRwRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 운영 저장소(JPA, RW/RO 분리 설정 그대로) — H2(MODE=MySQL). config/application.yml 의 master·replica 는 같은 H2 DB 라 replica 쪽 읽기가
 * master 쓰기를 본다. DDL 은 RW 의 ddl-auto: update 로 엔티티에서 만든다(운영은 DBA 의 platform.modu-platform.sql + validate).
 * 테스트 트랜잭션은 끈다 — RW 트랜잭션 안의 미커밋 행은 RO(다른 연결)에서 안 보이므로 실제처럼 메서드마다 커밋하고, 끝나면 표를 비운다.
 */
@DataJpaTest(properties = ["batch.store.capacity=5"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(RwJpaConfig::class, RoJpaConfig::class, JacksonAutoConfiguration::class, BatchPropertiesConfig::class, JpaBatchJobStore::class)
class JpaBatchJobStoreTest(
    @Autowired private val store: JpaBatchJobStore,
    @Autowired private val jobs: BatchJobRwRepository,
    @Autowired private val runs: BatchJobRunRwRepository,
    @Autowired private val changes: BatchJobChangeRwRepository,
) {
    private val t0 = Instant.parse("2026-10-17T03:00:00Z")

    @AfterEach
    fun clean() {
        runs.deleteAllInBatch()
        changes.deleteAllInBatch()
        jobs.deleteAllInBatch()
    }

    private fun job(service: String = "commerce-service", name: String = "point-outbox-relay", enabled: Boolean = true) =
        BatchJob(id = null, service = service, jobName = name, description = "아웃박스", cron = "0 */2 * * * *", enabled = enabled, timeoutSeconds = 120)

    private fun run(job: BatchJob, id: String, status: RunStatus = RunStatus.RUNNING, startedAt: Instant = t0) =
        BatchJobRun(id, job.id!!, job.service, job.jobName, RunTrigger.CRON, null, status, startedAt)

    @Test
    fun `saves and reads a job, lists sorted by service and name, updates and deletes`() {
        val a = store.saveJob(job(name = "push-campaigns"))
        val b = store.saveJob(job(name = "point-outbox-relay"))
        assertNotNull(a.id)
        assertNotNull(a.createdAt)

        assertEquals(listOf("point-outbox-relay", "push-campaigns"), store.jobs().map { it.jobName })
        assertEquals("0 */2 * * * *", store.job(b.id!!)!!.cron)

        val updated = store.saveJob(b.copy(cron = "0 0 4 * * *", enabled = false))
        assertEquals("0 0 4 * * *", store.job(b.id!!)!!.cron)
        assertFalse(updated.enabled)

        store.deleteJob(a.id!!)
        assertNull(store.job(a.id!!))
        assertEquals(1, store.jobs().size)
    }

    @Test
    fun `starts a run only when the job has no running run, then closes it`() {
        val j = store.saveJob(job())

        assertTrue(store.startRunIfNoneRunning(run(j, "job-aaaa000000000001")))
        assertFalse(store.startRunIfNoneRunning(run(j, "job-aaaa000000000002")), "RUNNING 이 있으면 두 번째는 거절")
        assertEquals(1, store.runningRuns().size)

        val closed = store.updateRun("job-aaaa000000000001") {
            it.copy(status = RunStatus.SUCCEEDED, finishedAt = t0.plusSeconds(3), durationMs = 3000, httpStatus = 200, result = mapOf("relayed" to 2))
        }
        assertEquals(RunStatus.SUCCEEDED, closed!!.status)
        assertEquals(mapOf("relayed" to 2), store.run("job-aaaa000000000001")!!.result)
        assertTrue(store.runningRuns().isEmpty())

        assertTrue(store.startRunIfNoneRunning(run(j, "job-aaaa000000000002")), "끝난 뒤에는 다시 시작할 수 있다")
        assertNull(store.updateRun("job-nope") { it })
    }

    @Test
    fun `lists runs newest first with filters and limit, and the last run per job`() {
        val a = store.saveJob(job(name = "a-job"))
        val b = store.saveJob(job(name = "b-job", service = "point-service"))
        for (i in 1..7) store.addRun(run(a, "job-a${i.toString().padStart(15, '0')}", RunStatus.SUCCEEDED, t0.plusSeconds(i.toLong())))
        store.addRun(run(b, "job-b00000000000001", RunStatus.FAILED, t0.plusSeconds(100)).copy(error = "boom"))
        store.addRun(run(b, "job-b00000000000002", RunStatus.SKIPPED, t0.plusSeconds(50)))

        val all = store.runs(jobId = null, service = null, status = null, limit = 100)
        assertEquals(5, all.size, "batch.store.capacity=5 로 잘린다")
        assertEquals("job-b00000000000001", all.first().id)

        assertEquals(listOf("job-b00000000000001"), store.runs(null, "point-service", RunStatus.FAILED, 10).map { it.id })
        assertEquals(3, store.runs(a.id, null, null, 3).size)
        assertEquals("job-a000000000000007", store.runs(a.id, null, null, 3).first().id)

        assertEquals("job-a000000000000007", store.lastRun(a.id!!)!!.id)
        assertEquals("job-b00000000000001", store.lastRun(b.id!!)!!.id)
        assertNull(store.lastRun(12345))
        assertEquals(listOf("a-job", "b-job"), store.jobsForScheduling().map { it.jobName }, "스케줄러는 master 에서 읽는다")
    }

    /** "잡당 RUNNING 하나" 의 진짜 보증은 DB 의 생성 열 + 유니크 키다. 사전 확인을 건너뛰고 바로 넣어도 DB 가 거절해야 한다. */
    @Test
    fun `the database itself rejects a second RUNNING row for the same job`() {
        val j = store.saveJob(job())
        assertTrue(store.startRunIfNoneRunning(run(j, "job-uk00000000000001")))

        val second = BatchJobRunEntity(id = "job-uk00000000000002", batchJobId = j.id!!, service = j.service, jobName = j.jobName, status = RunStatus.RUNNING.name, startedAt = t0)
        org.junit.jupiter.api.assertThrows<org.springframework.dao.DataIntegrityViolationException> { runs.saveAndFlush(second) }

        store.updateRun("job-uk00000000000001") { it.copy(status = RunStatus.SUCCEEDED, finishedAt = t0.plusSeconds(1)) }
        runs.saveAndFlush(BatchJobRunEntity(id = "job-uk00000000000003", batchJobId = j.id!!, service = j.service, jobName = j.jobName, status = RunStatus.RUNNING.name, startedAt = t0))
        assertEquals(1, store.runningRuns().size, "끝난 회차의 running_job_id 는 NULL 이 되어 다음 RUNNING 을 받는다")
    }

    @Test
    fun `records configuration changes newest first`() {
        val j = store.saveJob(job())
        store.addChange(BatchJobChange(null, j.id!!, "임준섭", t0, "cron", "0 */2 * * * *", "0 */5 * * * *"))
        store.addChange(BatchJobChange(null, j.id!!, "임준섭", t0.plusSeconds(10), "enabled", "true", "false"))

        val list = store.changes(j.id!!, 10)
        assertEquals(listOf("enabled", "cron"), list.map { it.field })
        assertEquals("0 */5 * * * *", list[1].after)
    }
}
