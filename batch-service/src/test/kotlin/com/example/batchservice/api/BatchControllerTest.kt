package com.example.batchservice.api

import com.example.batchservice.gateway.StaffLookup
import com.example.batchservice.job.BatchJobChange
import com.example.batchservice.job.BatchJobRun
import com.example.batchservice.job.BatchJobView
import com.example.batchservice.job.BatchService
import com.example.batchservice.job.RunStatus
import com.example.batchservice.job.RunTrigger
import com.example.batchservice.job.ServiceJobView
import com.example.batchservice.job.ServiceView
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant

/** 컨트롤러 + InternalTokenFilter + 오류 응답. 서비스 계층은 mock. */
@WebMvcTest(BatchController::class, properties = ["modu.internal-api.token=test-token"])
class BatchControllerTest(@Autowired private val mvc: MockMvc) {

    @MockitoBean
    lateinit var service: BatchService

    @MockitoBean
    lateinit var staff: StaffLookup

    private val now = Instant.parse("2026-10-17T03:00:00Z")
    private val run = BatchJobRun("job-0000000000000001", 7, "commerce-service", "point-outbox-relay", RunTrigger.CRON, null, RunStatus.SUCCEEDED, now, now.plusSeconds(1), 1000, 200, mapOf("outcome" to "ok"), null)
    private val view = BatchJobView(7, "commerce-service", "point-outbox-relay", "아웃박스", "0 */2 * * * *", "Asia/Seoul", true, 120, now.plusSeconds(120), run, now, now)

    @Test
    fun `rejects requests without the internal token`() {
        mvc.get("/api-system/batch/jobs").andExpect {
            status { isUnauthorized() }
            jsonPath("$.error") { value("unauthorized") }
        }
        mvc.post("/api-system/batch/jobs/7/run").andExpect { status { isUnauthorized() } }
        verify(service, never()).jobs()
    }

    @Test
    fun `lists jobs with next run and last run`() {
        whenever(service.jobs()).thenReturn(listOf(view))

        mvc.get("/api-system/batch/jobs") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isOk() }
            jsonPath("$.jobs[0].id") { value(7) }
            jsonPath("$.jobs[0].service") { value("commerce-service") }
            jsonPath("$.jobs[0].cron") { value("0 */2 * * * *") }
            jsonPath("$.jobs[0].nextRunAt") { value("2026-10-17T03:02:00Z") }
            jsonPath("$.jobs[0].lastRun.status") { value("SUCCEEDED") }
            jsonPath("$.jobs[0].lastRun.result.outcome") { value("ok") }
        }
    }

    @Test
    fun `creates a job with the staff name as changer and returns 201`() {
        whenever(staff.displayName("104614857372392207989")).thenReturn("임준섭")
        whenever(service.create(any(), eq("임준섭"))).thenReturn(view)

        mvc.post("/api-system/batch/jobs") {
            header("X-Internal-Token", "test-token")
            header("X-Auth-User-Id", "104614857372392207989")
            contentType = MediaType.APPLICATION_JSON
            content = """{"service":"commerce-service","jobName":"point-outbox-relay","cron":"0 */2 * * * *","enabled":true,"timeoutSeconds":120,"description":"아웃박스"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(7) }
        }
        val captor = argumentCaptor<CreateJobRequest>()
        verify(service).create(captor.capture(), eq("임준섭"))
        assert(captor.firstValue.jobName == "point-outbox-relay" && captor.firstValue.enabled == true)
    }

    @Test
    fun `validation errors from the service are 400 with the message`() {
        whenever(service.create(any(), any())).thenThrow(ApiException.badRequest("invalid_cron", "cron 식이 올바르지 않습니다"))

        mvc.post("/api-system/batch/jobs") {
            header("X-Internal-Token", "test-token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"service":"commerce-service","jobName":"x","cron":"* * *"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("invalid_cron") }
            jsonPath("$.message") { value("cron 식이 올바르지 않습니다") }
        }
    }

    @Test
    fun `patches and deletes a job`() {
        whenever(service.update(eq(7L), any(), any())).thenReturn(view.copy(enabled = false))

        mvc.patch("/api-system/batch/jobs/7") {
            header("X-Internal-Token", "test-token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"enabled":false}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.enabled") { value(false) }
        }
        val captor = argumentCaptor<UpdateJobRequest>()
        verify(service).update(eq(7L), captor.capture(), eq("unknown"))
        assert(captor.firstValue.enabled == false && captor.firstValue.cron == null)

        mvc.delete("/api-system/batch/jobs/7") { header("X-Internal-Token", "test-token") }.andExpect { status { isNoContent() } }
        verify(service).delete(7L, "unknown")
    }

    @Test
    fun `runs a job now with 202 and the run id, reads runs and a single run`() {
        whenever(service.runNow(7L, "unknown")).thenReturn(run.copy(status = RunStatus.RUNNING, finishedAt = null))
        whenever(service.runs(eq(7L), anyOrNull(), anyOrNull(), eq(20))).thenReturn(listOf(run))
        whenever(service.runs(anyOrNull(), eq("commerce-service"), eq(RunStatus.FAILED), eq(50))).thenReturn(emptyList())
        whenever(service.run("job-0000000000000001")).thenReturn(run)

        mvc.post("/api-system/batch/jobs/7/run") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isAccepted() }
            jsonPath("$.runId") { value("job-0000000000000001") }
            jsonPath("$.status") { value("RUNNING") }
        }
        mvc.get("/api-system/batch/jobs/7/runs") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isOk() }
            jsonPath("$.runs[0].id") { value("job-0000000000000001") }
            jsonPath("$.runs[0].durationMs") { value(1000) }
        }
        mvc.get("/api-system/batch/runs?service=commerce-service&status=FAILED&limit=50") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isOk() }
            jsonPath("$.runs") { isEmpty() }
        }
        mvc.get("/api-system/batch/runs/job-0000000000000001") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isOk() }
            jsonPath("$.trigger") { value("CRON") }
        }
    }

    @Test
    fun `changes, cron preview, services and service jobs`() {
        whenever(service.changes(7L, 50)).thenReturn(listOf(BatchJobChange(1, 7, "임준섭", now, "cron", "0 * * * * *", "0 */2 * * * *")))
        whenever(service.cronPreview("0 */2 * * * *", "Asia/Seoul", 3)).thenReturn(listOf(now.plusSeconds(120)))
        whenever(service.services()).thenReturn(listOf(ServiceView("commerce-service", "http://commerce-service:8200")))
        whenever(service.serviceJobs("commerce-service")).thenReturn(listOf(ServiceJobView("point-outbox-relay", "아웃박스")))

        mvc.get("/api-system/batch/jobs/7/changes") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isOk() }
            jsonPath("$.changes[0].field") { value("cron") }
            jsonPath("$.changes[0].after") { value("0 */2 * * * *") }
        }
        mvc.get("/api-system/batch/cron/preview?cron=0 */2 * * * *&zone=Asia/Seoul&count=3") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isOk() }
            jsonPath("$.next[0]") { value("2026-10-17T03:02:00Z") }
        }
        mvc.get("/api-system/batch/services") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isOk() }
            jsonPath("$.services[0].name") { value("commerce-service") }
        }
        mvc.get("/api-system/batch/services/commerce-service/jobs") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isOk() }
            jsonPath("$.jobs[0].name") { value("point-outbox-relay") }
        }
    }

    @Test
    fun `unknown job is 404`() {
        whenever(service.job(99L)).thenThrow(ApiException.notFound("job_not_found", "없는 잡입니다."))
        mvc.get("/api-system/batch/jobs/99") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("job_not_found") }
        }
    }
}
