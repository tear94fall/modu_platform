package com.example.batchservice.job

import java.time.Instant

/** 회차를 누가 시작했나. CRON = 스케줄러, MANUAL = 콘솔의 "지금 실행". */
enum class RunTrigger { CRON, MANUAL }

/**
 * 회차 상태. RUNNING → SUCCEEDED(2xx) | FAILED(4xx·5xx·연결 실패·재시작) | TIMEOUT(응답 대기 초과).
 * SKIPPED 는 서비스를 부르지 않은 회차 — 앞 회차가 아직 RUNNING 이거나 서비스가 409(이미 실행 중)로 답했다.
 */
enum class RunStatus { RUNNING, SUCCEEDED, FAILED, TIMEOUT, SKIPPED }

/**
 * 잡 정의 한 건(`batch_job`). 어느 서비스([service] = `modu.services.*` 의 이름)의 어느 잡([jobName] = 그 서비스 `GET /api-internal/v1/jobs` 의 name)을
 * 어떤 주기([cron], 6자리 Spring cron, [zone])로 돌리나. [enabled] 가 false 면 스케줄만 멈추고 수동 실행은 된다.
 */
data class BatchJob(
    val id: Long?,
    val service: String,
    val jobName: String,
    val description: String? = null,
    val cron: String,
    val zone: String = DEFAULT_ZONE,
    val enabled: Boolean = false,
    val timeoutSeconds: Int = 600,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    companion object {
        const val DEFAULT_ZONE = "Asia/Seoul"
    }
}

/**
 * 회차 한 건(`batch_job_run`). [id] 는 서비스에 보낸 `X-Request-Id`(`job-` 접두 16자) 그대로 — OpenSearch 에서 `attributes.requestId` 로 그 회차의 로그를 모두 본다.
 * [service]·[jobName] 은 잡 정의가 지워져도 이력이 읽히게 복사해 둔다. [result] 는 서비스가 돌려준 JSON 본문(commerce 의 JobRun 모양).
 */
data class BatchJobRun(
    val id: String,
    val batchJobId: Long,
    val service: String,
    val jobName: String,
    val trigger: RunTrigger,
    /** 수동이면 실행한 직원(member-service 로 푼 이름), cron 이면 null. */
    val triggeredBy: String?,
    val status: RunStatus,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val durationMs: Long? = null,
    val httpStatus: Int? = null,
    val result: Map<String, Any?>? = null,
    val error: String? = null,
)

/** 설정 변경 이력 한 건(`batch_job_change`). 생성은 field=`created`, 삭제는 `deleted`, 나머지는 바뀐 필드 이름(cron·zone·enabled·timeoutSeconds·description). */
data class BatchJobChange(
    val id: Long?,
    val batchJobId: Long,
    val changedBy: String,
    val changedAt: Instant,
    val field: String,
    val before: String?,
    val after: String?,
)
