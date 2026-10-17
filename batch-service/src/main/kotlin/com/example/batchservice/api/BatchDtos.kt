package com.example.batchservice.api

import com.example.batchservice.job.BatchJobChange
import com.example.batchservice.job.BatchJobRun
import com.example.batchservice.job.BatchJobView
import com.example.batchservice.job.ServiceJobView
import com.example.batchservice.job.ServiceView
import java.time.Instant

/** 잡 등록. 대상 서비스·잡 이름·주기는 필수, 나머지는 기본값(zone Asia/Seoul, enabled false, timeout batch.run.default-timeout-seconds). */
data class CreateJobRequest(
    val service: String?,
    val jobName: String?,
    val cron: String?,
    val zone: String? = null,
    val enabled: Boolean? = null,
    val timeoutSeconds: Int? = null,
    val description: String? = null,
)

/** 잡 수정. 보낸 필드만 바뀐다(서비스·잡 이름은 바꿀 수 없다 — 지우고 다시 등록). */
data class UpdateJobRequest(
    val cron: String? = null,
    val zone: String? = null,
    val enabled: Boolean? = null,
    val timeoutSeconds: Int? = null,
    val description: String? = null,
)

data class JobsResponse(val jobs: List<BatchJobView>)

data class RunsResponse(val runs: List<BatchJobRun>)

data class RunStartedResponse(val runId: String, val status: String)

data class ChangesResponse(val changes: List<BatchJobChange>)

data class CronPreviewResponse(val next: List<Instant>)

data class ServicesResponse(val services: List<ServiceView>)

data class ServiceJobsResponse(val jobs: List<ServiceJobView>)
