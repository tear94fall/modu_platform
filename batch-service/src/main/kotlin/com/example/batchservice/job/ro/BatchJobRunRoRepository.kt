package com.example.batchservice.job.ro

import com.example.batchservice.config.RoRepository
import com.example.batchservice.job.BatchJobRunEntity
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

/** replica(mysql-platform-replica, platform_ro). 실행 이력 목록·잡별 마지막 회차 — 몇 초 늦어도 되는 조회만. 필터는 Specification 으로 조합한다. */
interface BatchJobRunRoRepository : RoRepository<BatchJobRunEntity, String>, JpaSpecificationExecutor<BatchJobRunEntity> {

    /** 그 잡의 가장 최근 회차 한 건(idx_batch_job_run_job_started). */
    fun findFirstByBatchJobIdOrderByStartedAtDescIdDesc(batchJobId: Long): BatchJobRunEntity?
}
