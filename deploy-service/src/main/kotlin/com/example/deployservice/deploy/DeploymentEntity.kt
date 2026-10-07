package com.example.deployservice.deploy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * `modu-platform.deployment` 한 줄(modu_infra `data/mysql/schema/platform.modu-platform.sql` 의 DDL 그대로. 외래 키 없음, 앱은 validate 만).
 * [DeploymentRecord] 와의 변환은 [JpaDeploymentStore] 가 한다 — status/step 은 varchar 문자열, steps/rollout 은 JSON 문자열(json 컬럼),
 * 시각은 전부 UTC datetime(6).
 */
@Entity
@Table(name = "deployment")
class DeploymentEntity(
    @Id
    @Column(name = "id", length = 40, nullable = false)
    var id: String = "",

    @Column(name = "service", length = 64, nullable = false)
    var service: String = "",

    @Column(name = "tag", length = 128, nullable = false)
    var tag: String = "",

    @Column(name = "previous_tag", length = 128)
    var previousTag: String? = null,

    /** 표시 이름(member-service 로 푼 이름/이메일). */
    @Column(name = "by_name", length = 128, nullable = false)
    var byName: String = "",

    /** 게이트웨이 X-Auth-User-Id(구글 sub). */
    @Column(name = "by_id", length = 128)
    var byId: String? = null,

    /** RUNNING | SUCCEEDED | FAILED ([DeploymentStatus].name). */
    @Column(name = "status", length = 16, nullable = false)
    var status: String = DeploymentStatus.RUNNING.name,

    /** COMMIT | SYNC | ROLLOUT | DONE ([Step].name). */
    @Column(name = "step", length = 16, nullable = false)
    var step: String = Step.COMMIT.name,

    @Column(name = "percent", nullable = false)
    var percent: Int = 0,

    @Column(name = "started_at", nullable = false, columnDefinition = "datetime(6)")
    var startedAt: Instant = Instant.EPOCH,

    @Column(name = "finished_at", columnDefinition = "datetime(6)")
    var finishedAt: Instant? = null,

    @Column(name = "error", columnDefinition = "text")
    var error: String? = null,

    @Column(name = "commit_sha", length = 64)
    var commitSha: String? = null,

    @Column(name = "commit_url", length = 255)
    var commitUrl: String? = null,

    /** List<StepRecord> 를 JSON 으로. JdbcTypeCode(JSON) 이라 MySQL 은 cast(? as json), H2 는 ? format json 으로 묶인다(문자열은 그대로 통과). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "steps_json", nullable = false, columnDefinition = "json")
    var stepsJson: String = "[]",

    /** RolloutView 스냅샷 JSON(없으면 null). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rollout_json", columnDefinition = "json")
    var rolloutJson: String? = null,

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
