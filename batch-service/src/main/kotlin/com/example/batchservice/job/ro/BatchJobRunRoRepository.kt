package com.example.batchservice.job.ro

import com.example.batchservice.config.RoRepository
import com.example.batchservice.job.BatchJobRunEntity
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query

/** replica(mysql-platform-replica, platform_ro). 실행 이력 목록·잡별 마지막 회차 — 몇 초 늦어도 되는 조회만. 필터는 Specification 으로 조합한다. */
interface BatchJobRunRoRepository : RoRepository<BatchJobRunEntity, String>, JpaSpecificationExecutor<BatchJobRunEntity> {

    /** 잡별 가장 최근(startedAt) 회차. 같은 시각이 둘이면 둘 다 나오므로 부르는 쪽이 하나로 줄인다. */
    @Query("select r from BatchJobRunEntity r where r.startedAt = (select max(r2.startedAt) from BatchJobRunEntity r2 where r2.batchJobId = r.batchJobId)")
    fun findLastPerJob(): List<BatchJobRunEntity>
}
