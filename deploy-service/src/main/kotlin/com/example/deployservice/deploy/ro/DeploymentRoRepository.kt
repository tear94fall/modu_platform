package com.example.deployservice.deploy.ro

import com.example.deployservice.config.RoRepository
import com.example.deployservice.deploy.DeploymentEntity
import org.springframework.data.domain.Pageable

/** replica(mysql-platform-replica, platform_ro). 이력 목록·서비스별 최근 배포처럼 몇 초 늦어도 되는 조회만. 쓰기 메서드는 없다. */
interface DeploymentRoRepository : RoRepository<DeploymentEntity, String> {

    /** 최신순(idx_deployment_started). 같은 시각이면 먼저 저장된 것이 뒤로. */
    fun findAllByOrderByStartedAtDescCreatedAtDesc(pageable: Pageable): List<DeploymentEntity>

    /** 서비스 하나의 최신순(idx_deployment_service_started). */
    fun findByServiceOrderByStartedAtDescCreatedAtDesc(service: String, pageable: Pageable): List<DeploymentEntity>

    fun findFirstByServiceAndStatusOrderByStartedAtDescCreatedAtDesc(service: String, status: String): DeploymentEntity?

    fun count(): Long
}
