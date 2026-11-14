package com.example.deployservice.config

import io.temporal.common.converter.DataConverter
import io.temporal.spring.boot.TemporalOptionsCustomizer
import io.temporal.worker.NonDeterministicException
import io.temporal.worker.WorkflowImplementationOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Temporal 빈. 연결·워커 자체는 temporal-spring-boot-starter 가 spring.temporal.* 로 만든다(워크플로·액티비티는 deploy/workflow 패키지 자동 등록).
 *
 * - [dataConverter]: Kotlin data class 페이로드(TemporalConverters). 스타터가 DataConverter 빈을 집어 WorkflowClient·워커에 꽂는다.
 * - [workflowImplementationCustomizer]: 비결정성 재생은 바로 실패(아래).
 * - [temporalHealthIndicator]: `/actuator/health` 의 `temporal`. **readiness 그룹에는 넣지 않는다**(application.yml) — Temporal 이 잠깐 끊겨도
 *   서비스 목록·태그·이력 조회는 멀쩡해야 하고, Temporal 은 Recreate 전략이라 재배포 때 끊긴다. 배포 시작만 DeploymentStarter 가 503 으로 거절한다.
 */
@Configuration
class TemporalConfig {

    @Bean
    fun dataConverter(): DataConverter = TemporalConverters.kotlinDataConverter()

    /**
     * 워크플로 코드를 바꾼 뒤 자기 자신을 배포하면 새 파드가 진행 중인 `deploy-deploy-service` 실행을 **재생**한다. 액티비티 순서·타이머가 달라졌으면
     * NonDeterministicException — 기본 정책은 워크플로 태스크 실패를 되풀이해 콘솔이 30분(실행 타임아웃) 동안 스피너를 돈다.
     * 대신 워크플로를 바로 실패로 끝내 기록이 닫히고(records.fail 은 안 돌지만 다음 배포 시작 때 고아 정리가 닫는다) 다시 배포할 수 있게 한다.
     * 운영 규칙(README): 워크플로 코드 변경은 Workflow.getVersion 으로 분기하거나, 배포 전에 deploy-deploy-service 가 실행 중이 아님을 확인한다.
     */
    @Bean
    fun workflowImplementationCustomizer(): TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder> =
        TemporalOptionsCustomizer { builder -> builder.setFailWorkflowExceptionTypes(NonDeterministicException::class.java) }

    @Bean
    fun temporalHealthIndicator(stubs: WorkflowServiceStubs): HealthIndicator = HealthIndicator {
        try {
            val status = stubs.healthCheck().status
            if (status == io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING) Health.up().build()
            else Health.down().withDetail("status", status.name).build()
        } catch (e: Exception) {
            Health.down(e).build()
        }
    }
}
