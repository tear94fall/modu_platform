package com.example.gatewayservice.filter

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * 경로에 "." 또는 ".." 세그먼트(퍼센트 인코딩 포함)가 있으면 400. 라우트 매칭 전에 실행돼
 * /api-public/../api-internal 같은 우회를 게이트웨이에서 먼저 막는다. 서비스 쪽 필터의 이중 방어.
 */
@Component
class DotSegmentRejectFilter : GlobalFilter, Ordered {

    companion object {
        fun hasDotSegment(rawPath: String?): Boolean {
            if (rawPath == null) return false
            return rawPath.split("/").any { seg ->
                val s = seg.replace("%2e", ".").replace("%2E", ".")
                s == "." || s == ".."
            }
        }
    }

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        if (hasDotSegment(exchange.request.uri.rawPath)) {
            exchange.response.statusCode = HttpStatus.BAD_REQUEST
            return exchange.response.setComplete()
        }
        return chain.filter(exchange)
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
}
