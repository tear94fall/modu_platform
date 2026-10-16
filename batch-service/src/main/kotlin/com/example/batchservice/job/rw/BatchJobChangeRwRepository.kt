package com.example.batchservice.job.rw

import com.example.batchservice.config.RwRepository
import com.example.batchservice.job.BatchJobChangeEntity

/** master(mysql-platform). 변경 이력 쓰기. */
interface BatchJobChangeRwRepository : RwRepository<BatchJobChangeEntity, Long>
