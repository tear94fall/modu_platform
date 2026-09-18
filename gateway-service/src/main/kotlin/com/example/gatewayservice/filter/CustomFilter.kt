package com.example.gatewayservice.filter

import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/** 요청 id 와 응답 상태를 남기는 로깅 필터. 라우트에 `CustomFilter` 로 붙인다. */
@Component
class CustomFilter : AbstractGatewayFilterFactory<CustomFilter.Config>(Config::class.java) {

    private val log = LoggerFactory.getLogger(CustomFilter::class.java)

    override fun apply(config: Config): GatewayFilter =
        GatewayFilter { exchange, chain ->
            val request = exchange.request
            val response = exchange.response

            log.info("Custom Filter request id -> {}", request.id)

            chain.filter(exchange).then(
                Mono.fromRunnable {
                    log.info("Custom Filter response status code -> {}", response.statusCode)
                },
            )
        }

    class Config
}
