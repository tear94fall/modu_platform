package com.example.batchservice.job.ro

import com.example.batchservice.config.RoRepository
import com.example.batchservice.job.BatchJobChangeEntity
import org.springframework.data.domain.Pageable

/** replica. 잡 하나의 변경 이력(최신순). */
interface BatchJobChangeRoRepository : RoRepository<BatchJobChangeEntity, Long> {
    fun findByBatchJobIdOrderByChangedAtDescIdDesc(batchJobId: Long, pageable: Pageable): List<BatchJobChangeEntity>
}
