package com.example.deployservice.deploy

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
 * `modu-platform.deployment` 한 줄(modu_infra `data/mysql/schema/platform.modu-platform.sql` 의 DDL 그대로. 외래 키 없음, 앱은 validate 만).
 * [DeploymentRecord] 와의 변환은 [JpaDeploymentStore] 가 한다 — status/step 은 varchar 문자열, steps/rollout 은 JSON 문자열(json 컬럼),
 * 시각은 전부 UTC datetime(6).
 */
@Entity
// 유니크 키는 서비스당 RUNNING 하나를 지키는 수문장이라 다른 레포 엔티티들처럼 여기에 선언해 둔다(실제 DDL 은 DBA 소유:
// modu_infra data/mysql/schema/platform.modu-platform.sql, ddl-auto 는 validate). 조회용 인덱스 3개는 그 기준선에만 있다.
@Table(
    name = "deployment",
    uniqueConstraints = [UniqueConstraint(name = "uk_deployment_running_service", columnNames = ["running_service"])],
)
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

    /**
     * 서비스당 RUNNING 하나를 지키는 수문장. DB 가 만드는 생성 열이라 앱은 읽기만 한다(insertable/updatable = false):
     *
     * ```sql
     * running_service varchar(64) GENERATED ALWAYS AS (CASE WHEN status = 'RUNNING' THEN service END) STORED,
     * UNIQUE KEY uk_deployment_running_service (running_service)
     * ```
     *
     * status 가 RUNNING 이면 service 이름, 아니면 NULL(MySQL 유니크 키는 NULL 을 여러 개 허용한다). 그래서 같은 서비스의 두 번째 RUNNING 은
     * INSERT 가 거절되고([JpaDeploymentStore.addIfNoneRunning] 가 409 로 바꾼다), 배포가 끝나 status 가 바뀌면 값이 저절로 NULL 이 된다 —
     * 앱이 치우지 않는다. 매핑해 두는 이유는 ddl-auto validate 가 기동 때 이 열이 정말 있는지 확인해 주기 때문(스키마를 안 올리면 바로 실패).
     *
     * 주의: 테스트(H2, ddl-auto update)에서는 그냥 nullable varchar 로 만들어진다 — 생성 열도 유니크 키도 없다. H2 로 "DB 가 막아 준다"를
     * 증명하는 테스트는 쓰지 않는다(실제 확인은 dev MySQL 에서).
     */
    @Column(name = "running_service", length = 64, insertable = false, updatable = false)
    var runningService: String? = null,

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
