package com.example.deployservice.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * readiness(/actuator/health/readiness)가 보는 DB indicator `masterDb`. 자동 `db` 는 master·replica 를 모두 검사해 레플리카가 늦거나 죽으면
 * 서비스 전체가 unready 가 되므로, master 풀만 보는 이것만 readiness 그룹에 넣는다(application.yml). 다른 서비스(chat·commerce)와 같은 규칙.
 * 지연 프록시가 아니라 실제 Hikari 풀을 봐서 검사 때 바로 연결을 확인한다.
 */
@Configuration
class MasterDbHealthConfig {

    @Bean
    fun masterDbHealthIndicator(@Qualifier("rwHikariDataSource") master: HikariDataSource): HealthIndicator =
        DataSourceHealthIndicator(master)
}
