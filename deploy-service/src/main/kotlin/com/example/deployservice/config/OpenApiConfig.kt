package com.example.deployservice.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * springdoc 이 GET /v3/api-docs 로 내보내는 OpenAPI 문서의 머리말.
 * 서비스 포트(클러스터 안)에서만 열리고, 밖에서는 게이트웨이가 시스템 콘솔 권한으로 모아 보여 준다.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(@Value("\${spring.application.name:deploy-service}") name: String): OpenAPI =
        OpenAPI().info(
            Info()
                .title(name)
                .version("v1")
                .description("모두 시스템 배포 탭의 백엔드입니다. GHCR 태그 조회, modu_infra 태그 커밋, Argo CD Sync, 롤아웃 진행률을 제공합니다."),
        )
}
