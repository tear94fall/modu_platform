package com.example.batchservice.job

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * `modu-platform.batch_job_run` 한 줄. 외래 키 없음(잡 정의를 지워도 이력은 남는다).
 *
 * [runningJobId] 는 잡당 RUNNING 하나를 지키는 수문장 — DB 가 만드는 생성 열이라 앱은 읽기만 한다:
 * ```sql
 * running_job_id bigint GENERATED ALWAYS AS (CASE WHEN status = 'RUNNING' THEN batch_job_id END) STORED,
 * UNIQUE KEY uk_batch_job_run_running (running_job_id)
 * ```
 * 같은 잡의 두 번째 RUNNING INSERT 는 DB 가 거절하고([JpaBatchJobStore.startRunIfNoneRunning] 이 false 로 바꾼다), 회차가 끝나 status 가 바뀌면 값이 저절로 NULL 이 된다.
 * 테스트(H2, ddl-auto update)에서는 그냥 nullable 열이라 유니크 키가 없다 — "DB 가 막아 준다"는 dev MySQL 에서 확인한다.
 */
@Entity
@Table(name = "batch_job_run", uniqueConstraints = [UniqueConstraint(name = "uk_batch_job_run_running", columnNames = ["running_job_id"])])
class BatchJobRunEntity(
    @Id
    @Column(name = "id", length = 40, nullable = false)
    var id: String = "",

    @Column(name = "batch_job_id", nullable = false)
    var batchJobId: Long = 0,

    @Column(name = "service", length = 64, nullable = false)
    var service: String = "",

    @Column(name = "job_name", length = 64, nullable = false)
    var jobName: String = "",

    /** CRON | MANUAL ([RunTrigger].name). */
    @Column(name = "trigger_type", length = 16, nullable = false)
    var triggerType: String = RunTrigger.CRON.name,

    @Column(name = "triggered_by", length = 128)
    var triggeredBy: String? = null,

    /** RUNNING | SUCCEEDED | FAILED | TIMEOUT | SKIPPED ([RunStatus].name). */
    @Column(name = "status", length = 16, nullable = false)
    var status: String = RunStatus.RUNNING.name,

    @Column(name = "started_at", nullable = false, columnDefinition = "datetime(6)")
    var startedAt: Instant = Instant.EPOCH,

    @Column(name = "finished_at", columnDefinition = "datetime(6)")
    var finishedAt: Instant? = null,

    @Column(name = "duration_ms")
    var durationMs: Long? = null,

    @Column(name = "http_status")
    var httpStatus: Int? = null,

    /** 서비스가 돌려준 JSON 본문. JdbcTypeCode(JSON) 이라 MySQL 은 cast(? as json), H2 는 ? format json. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "json")
    var resultJson: String? = null,

    @Column(name = "error", columnDefinition = "text")
    var error: String? = null,

    @Column(name = "running_job_id", insertable = false, updatable = false)
    var runningJobId: Long? = null,

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    var createdAt: Instant = Instant.EPOCH,

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    var updatedAt: Instant = Instant.EPOCH,
) {
    @PrePersist
    fun prePersist() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}
