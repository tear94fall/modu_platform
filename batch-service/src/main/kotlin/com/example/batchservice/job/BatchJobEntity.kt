package com.example.batchservice.job

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/** `modu-platform.batch_job` 한 줄(DDL 은 modu_infra `data/mysql/schema/platform.modu-platform.sql`, 앱은 validate 만). [BatchJob] 과의 변환은 [JpaBatchJobStore]. */
@Entity
@Table(name = "batch_job", uniqueConstraints = [UniqueConstraint(name = "uk_batch_job_service_name", columnNames = ["service", "job_name"])])
class BatchJobEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "service", length = 64, nullable = false)
    var service: String = "",

    @Column(name = "job_name", length = 64, nullable = false)
    var jobName: String = "",

    @Column(name = "description", length = 255)
    var description: String? = null,

    @Column(name = "cron", length = 64, nullable = false)
    var cron: String = "",

    @Column(name = "zone", length = 64, nullable = false)
    var zone: String = BatchJob.DEFAULT_ZONE,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false,

    @Column(name = "timeout_seconds", nullable = false)
    var timeoutSeconds: Int = 600,

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
