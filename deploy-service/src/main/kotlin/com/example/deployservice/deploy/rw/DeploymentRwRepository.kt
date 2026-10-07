package com.example.deployservice.deploy.rw

import com.example.deployservice.config.RwRepository
import com.example.deployservice.deploy.DeploymentEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** master(mysql-platform). 모든 쓰기, 진행률 조회(get/update), 재시작 복구가 쓴다 — 복제 지연이 없어야 하는 경로. */
interface DeploymentRwRepository : RwRepository<DeploymentEntity, String> {

    /** 읽기-바꾸기-쓰기용(SELECT … FOR UPDATE). 같은 기록을 두 스레드가 동시에 고치지 않게. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeploymentEntity d where d.id = :id")
    fun findForUpdate(@Param("id") id: String): DeploymentEntity?

    /** 재시작 복구용(idx_deployment_status). master 에서 읽어야 방금 RUNNING 으로 남은 행을 놓치지 않는다. */
    fun findByStatus(status: String): List<DeploymentEntity>
}
