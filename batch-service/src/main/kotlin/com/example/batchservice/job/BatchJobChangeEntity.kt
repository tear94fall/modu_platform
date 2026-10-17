package com.example.batchservice.job

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** `modu-platform.batch_job_change` 한 줄 — 누가 언제 어느 필드를 무엇에서 무엇으로 바꿨나. 외래 키 없음. */
@Entity
@Table(name = "batch_job_change")
class BatchJobChangeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "batch_job_id", nullable = false)
    var batchJobId: Long = 0,

    @Column(name = "changed_by", length = 128, nullable = false)
    var changedBy: String = "",

    @Column(name = "changed_at", nullable = false, columnDefinition = "datetime(6)")
    var changedAt: Instant = Instant.EPOCH,

    @Column(name = "field", length = 32, nullable = false)
    var field: String = "",

    @Column(name = "before_value", length = 255)
    var beforeValue: String? = null,

    @Column(name = "after_value", length = 255)
    var afterValue: String? = null,
)
