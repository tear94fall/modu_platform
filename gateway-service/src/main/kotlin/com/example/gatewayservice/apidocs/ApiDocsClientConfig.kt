package com.example.gatewayservice.apidocs

import org.springframework.cloud.client.loadbalancer.LoadBalanced
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

/**
 * 서비스 문서(`/v3/api-docs`)를 Eureka 로 찾아 부르는 WebClient.Builder.
 * `defaultCandidate = false` 라서 스프링 부트의 기본 WebClient.Builder 를 밀어내지 않고, `@LoadBalanced` 로 콕 집어야만 주입된다.
 */
@Configuration
class ApiDocsClientConfig {

    @Bean(defaultCandidate = false)
    @LoadBalanced
    fun loadBalancedWebClientBuilder(): WebClient.Builder = WebClient.builder()
}
