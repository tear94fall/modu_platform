package com.example.batchservice.job.rw

import com.example.batchservice.config.RwRepository
import com.example.batchservice.job.BatchJobEntity

/** master(mysql-platform). 잡 정의의 쓰기와, 방금 쓴 것을 바로 읽는 조회(단건·스케줄러 기동 때 전체). */
interface BatchJobRwRepository : RwRepository<BatchJobEntity, Long> {
    fun findAllByOrderByServiceAscJobNameAsc(): List<BatchJobEntity>

    fun existsByServiceAndJobName(service: String, jobName: String): Boolean
}
