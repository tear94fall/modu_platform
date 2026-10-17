package com.example.batchservice.job.rw

import com.example.batchservice.config.RwRepository
import com.example.batchservice.job.BatchJobRunEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** master(mysql-platform). 회차의 시작·종료 쓰기, 단건 조회(콘솔이 수동 실행 뒤 폴링), 재시작 복구 — 복제 지연이 없어야 하는 경로. */
interface BatchJobRunRwRepository : RwRepository<BatchJobRunEntity, String> {

    /** 읽기-바꾸기-쓰기용(SELECT … FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BatchJobRunEntity r where r.id = :id")
    fun findForUpdate(@Param("id") id: String): BatchJobRunEntity?

    /** 시작 전 빠른 확인(흔한 경우에 유니크 키를 건드리지 않고 SKIPPED 를 준다). 진짜 보증은 DB 유니크 키 uk_batch_job_run_running 이다. */
    fun existsByBatchJobIdAndStatus(batchJobId: Long, status: String): Boolean

    /** 재시작 복구용(idx_batch_job_run_status). */
    fun findByStatus(status: String): List<BatchJobRunEntity>
}
