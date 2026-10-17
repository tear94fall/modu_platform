package com.example.batchservice.job.ro

import com.example.batchservice.config.RoRepository
import com.example.batchservice.job.BatchJobEntity

/** replica(mysql-platform-replica, platform_ro). 콘솔의 잡 목록(몇 초 늦어도 된다). 쓰기 메서드는 없다. */
interface BatchJobRoRepository : RoRepository<BatchJobEntity, Long> {
    fun findAllByOrderByServiceAscJobNameAsc(): List<BatchJobEntity>
}
