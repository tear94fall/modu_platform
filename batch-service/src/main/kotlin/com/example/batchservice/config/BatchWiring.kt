package com.example.batchservice.config

import com.example.batchservice.gateway.MemberServiceStaffLookup
import com.example.batchservice.gateway.RestClientServiceJobsGateway
import com.example.batchservice.gateway.ServiceJobsGateway
import com.example.batchservice.gateway.StaffLookup
import com.example.batchservice.job.BatchJobStore
import com.example.batchservice.job.BatchService
import com.example.batchservice.run.BatchRunner
import com.example.batchservice.run.CronScheduler
import com.example.batchservice.run.JobCaller
import com.example.batchservice.run.RestClientJobCaller
import com.example.batchservice.run.StaleRunRecovery
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration

/** 배치 파이프라인 빈. 서비스 호출([JobCaller])·직원 이름·서비스 잡 목록은 인터페이스로 두어 테스트가 가짜로 바꿔 끼운다. */
@Configuration
class BatchWiring {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /**
     * 회차를 돌리는 스레드 풀(cron 트리거·수동 실행·리컨사일·고아 회차 정리가 같이 쓴다). 종료 때 돌고 있는 회차는 끝까지 기다리고(graceful shutdown 20초 안에서),
     * 아직 울리지 않은 틱은 버린다 — 죽는 파드가 새 RUNNING 행을 만들지 않게.
     */
    @Bean(destroyMethod = "shutdown")
    fun batchTaskScheduler(properties: BatchProperties): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        setPoolSize(properties.scheduler.poolSize)
        setThreadNamePrefix("batch-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(20)
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        initialize()
    }

    @Bean
    fun jobCaller(properties: BatchProperties, modu: ModuProperties): JobCaller =
        RestClientJobCaller(RestClientJobCaller.withTimeouts(Duration.ofSeconds(properties.run.connectTimeoutSeconds.toLong())), modu.internalApi.token)

    @Bean
    fun batchRunner(store: BatchJobStore, caller: JobCaller, modu: ModuProperties, properties: BatchProperties, clock: Clock, objectMapper: ObjectMapper): BatchRunner =
        BatchRunner(store, caller, modu, properties, clock, objectMapper)

    @Bean
    fun cronScheduler(store: BatchJobStore, runner: BatchRunner, lock: LockingTaskExecutor, pool: ThreadPoolTaskScheduler, clock: Clock, properties: BatchProperties): CronScheduler =
        CronScheduler(store, runner, lock, pool, clock, properties)

    @Bean
    fun staleRunRecovery(store: BatchJobStore, properties: BatchProperties, clock: Clock, pool: ThreadPoolTaskScheduler): StaleRunRecovery =
        StaleRunRecovery(store, properties, clock, pool)

    /** 서비스 잡 목록 중계용 클라이언트(내부 토큰). */
    @Bean
    fun serviceJobsGateway(modu: ModuProperties): ServiceJobsGateway = RestClientServiceJobsGateway(
        RestClient.builder().requestFactory(requestFactory()).defaultHeader("X-Internal-Token", modu.internalApi.token).build(),
    )

    /** 실행자·변경자 이름 조회용 member-service 클라이언트(주소 modu.services.member-service, 내부 토큰). */
    @Bean
    fun staffLookup(modu: ModuProperties): StaffLookup {
        val url = modu.serviceUrl("member-service") ?: "http://member-service:8080"
        return MemberServiceStaffLookup(RestClient.builder().baseUrl(url).requestFactory(requestFactory()).defaultHeader("X-Internal-Token", modu.internalApi.token).build())
    }

    @Bean
    fun batchService(store: BatchJobStore, scheduler: CronScheduler, modu: ModuProperties, properties: BatchProperties, gateway: ServiceJobsGateway, clock: Clock): BatchService =
        BatchService(store, scheduler, modu, properties, gateway, clock)

    private fun requestFactory() = SimpleClientHttpRequestFactory().apply {
        setConnectTimeout(Duration.ofSeconds(5))
        setReadTimeout(Duration.ofSeconds(10))
    }
}
