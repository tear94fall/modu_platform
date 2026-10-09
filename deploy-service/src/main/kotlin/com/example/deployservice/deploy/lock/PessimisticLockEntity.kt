package com.example.deployservice.deploy.lock

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * `modu-platform.pessimistic_lock` 한 줄 — [PessimisticLock] 이 잠그는 이름 하나(modu_infra `data/mysql/schema/platform.modu-platform.sql` 의 DDL 그대로).
 * 앱은 이 엔티티로 읽고 쓰지 않는다(PessimisticLock 이 네이티브 SQL 로 다룬다). 운영에선 ddl-auto validate 가 표 모양을 확인하고,
 * 테스트(H2, ddl-auto update)에선 이것으로 표가 만들어진다.
 */
@Entity
@Table(name = "pessimistic_lock")
class PessimisticLockEntity(
    @Id
    @Column(name = "name", length = 100, nullable = false)
    var name: String = "",

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    var createdAt: Instant = Instant.EPOCH,
)
