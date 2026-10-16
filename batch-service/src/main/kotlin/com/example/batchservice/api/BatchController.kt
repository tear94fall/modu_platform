package com.example.batchservice.api

import com.example.batchservice.gateway.StaffLookup
import com.example.batchservice.job.BatchJobRun
import com.example.batchservice.job.BatchJobView
import com.example.batchservice.job.BatchService
import com.example.batchservice.job.RunStatus
import com.example.batchservice.logging.RequestContextFilter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 모두 시스템 "배치" 탭. 게이트웨이 라우트 batch-service-system(`/batch-service/api-system/...`, ROLE_SYSTEM)이
 * 내부 토큰과 직원 식별(X-Auth-User-Id)을 붙여 넘긴다. 토큰은 [InternalTokenFilter] 가 본다.
 */
@RestController
@RequestMapping("/api-system/batch")
@Tag(name = "batch", description = "배치 잡 정의(주기·사용 여부·타임아웃)·수동 실행·실행 이력·변경 이력")
class BatchController(private val service: BatchService, private val staff: StaffLookup) {

    companion object {
        const val UNKNOWN_USER = "unknown"
    }

    @Operation(summary = "잡 목록(다음 실행 시각·마지막 회차 포함)")
    @GetMapping("/jobs")
    fun jobs(): JobsResponse = JobsResponse(service.jobs())

    @Operation(summary = "잡 등록(201). cron 은 6자리 Spring 식, zone 기본 Asia/Seoul, enabled 기본 false")
    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody req: CreateJobRequest,
        @RequestHeader(RequestContextFilter.USER_ID_HEADER, required = false) userId: String?,
    ): BatchJobView = service.create(req, displayName(userId))

    @Operation(summary = "잡 한 건")
    @GetMapping("/jobs/{id}")
    fun job(@PathVariable id: Long): BatchJobView = service.job(id)

    @Operation(summary = "잡 수정 — 보낸 필드만. 주기·사용 여부가 바뀌면 트리거를 즉시 다시 건다")
    @PatchMapping("/jobs/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestBody req: UpdateJobRequest,
        @RequestHeader(RequestContextFilter.USER_ID_HEADER, required = false) userId: String?,
    ): BatchJobView = service.update(id, req, displayName(userId))

    @Operation(summary = "잡 삭제(204). 실행 이력은 남는다")
    @DeleteMapping("/jobs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: Long,
        @RequestHeader(RequestContextFilter.USER_ID_HEADER, required = false) userId: String?,
    ) = service.delete(id, displayName(userId))

    @Operation(summary = "지금 실행(202). RUNNING 회차 id 를 돌려주고 호출은 뒤에서 이어 간다 — GET /runs/{runId} 로 폴링. 이미 돌고 있으면 SKIPPED")
    @PostMapping("/jobs/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun runNow(
        @PathVariable id: Long,
        @RequestHeader(RequestContextFilter.USER_ID_HEADER, required = false) userId: String?,
    ): RunStartedResponse = service.runNow(id, displayName(userId)).let { RunStartedResponse(it.id, it.status.name) }

    @Operation(summary = "잡 하나의 실행 이력(최신순)")
    @GetMapping("/jobs/{id}/runs")
    fun jobRuns(
        @PathVariable id: Long,
        @RequestParam(required = false) status: RunStatus?,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
    ): RunsResponse = RunsResponse(service.runs(id, null, status, limit))

    @Operation(summary = "잡 하나의 설정 변경 이력(최신순)")
    @GetMapping("/jobs/{id}/changes")
    fun changes(
        @PathVariable id: Long,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): ChangesResponse = ChangesResponse(service.changes(id, limit))

    @Operation(summary = "실행 이력 전체(최신순). service·status 로 거른다")
    @GetMapping("/runs")
    fun runs(
        @RequestParam(required = false) service: String?,
        @RequestParam(required = false) status: RunStatus?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): RunsResponse = RunsResponse(this.service.runs(null, service, status, limit))

    @Operation(summary = "회차 한 건(수동 실행 뒤 폴링). id 가 서비스에 보낸 X-Request-Id 라 OpenSearch 에서 attributes.requestId 로 그 회차의 로그를 본다")
    @GetMapping("/runs/{runId}")
    fun run(@PathVariable runId: String): BatchJobRun = service.run(runId)

    @Operation(summary = "cron 미리보기 — 다음 실행 시각 count 개(기본 5). 식이 틀리면 400")
    @GetMapping("/cron/preview")
    fun cronPreview(
        @RequestParam cron: String,
        @RequestParam(required = false) zone: String?,
        @RequestParam(required = false, defaultValue = "5") count: Int,
    ): CronPreviewResponse = CronPreviewResponse(service.cronPreview(cron, zone, count))

    @Operation(summary = "잡을 등록할 수 있는 서비스(modu.services)")
    @GetMapping("/services")
    fun services(): ServicesResponse = ServicesResponse(service.services())

    @Operation(summary = "그 서비스가 돌릴 수 있는 잡 목록(서비스의 GET /api-internal/v1/jobs 를 중계)")
    @GetMapping("/services/{name}/jobs")
    fun serviceJobs(@PathVariable name: String): ServiceJobsResponse = ServiceJobsResponse(service.serviceJobs(name))

    private fun displayName(userId: String?): String {
        val id = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return UNKNOWN_USER
        return staff.displayName(id) ?: id
    }
}
