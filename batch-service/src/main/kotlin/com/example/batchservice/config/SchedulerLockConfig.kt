package com.example.batchservice.config

import com.zaxxer.hikari.HikariDataSource
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/**
 * ShedLock. 파드마다 DB 의 잡을 전부 cron 으로 걸기 때문에 파드가 2개면 같은 회차가 두 번 울린다 — 회차 본문을 잡 단위 이름(`batch:job-<id>`)의
 * `shedlock` 행으로 감싸 먼저 잡은 파드만 돌린다(CronScheduler). `@Scheduled` 를 쓰지 않으므로 @EnableSchedulerLock 대신 [LockingTaskExecutor] 를 직접 쓴다.
 * 락은 반드시 master(rw) 풀에 — 레플리카는 읽기 전용이고 복제 지연이 있어 두 파드가 동시에 통과한다. `usingDbTime()` 으로 파드 시계 차이도 피한다.
 */
@Configuration
class SchedulerLockConfig {

    @Bean
    fun lockProvider(@Qualifier("rwHikariDataSource") master: HikariDataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(master))
                .usingDbTime()
                .build(),
        )

    @Bean
    fun lockingTaskExecutor(lockProvider: LockProvider): LockingTaskExecutor = DefaultLockingTaskExecutor(lockProvider)
}
