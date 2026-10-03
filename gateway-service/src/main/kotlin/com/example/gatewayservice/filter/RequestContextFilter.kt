package com.example.gatewayservice.filter

import net.logstash.logback.argument.StructuredArguments.kv
import net.logstash.logback.argument.StructuredArguments.v
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.ErrorResponse
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * 요청마다 요청 id(X-Request-Id)를 정하고, 응답이 끝나면 접근 로그(http.access) 한 줄을 남긴다.
 *
 * - 클라이언트가 보낸 X-Request-Id 는 글자·길이([A-Za-z0-9_-], 64자 이하)가 맞을 때만 쓰고, 아니면 새로 만든다
 *   (로그 인젝션·과대 헤더 방지). 뒤 서비스로 넘기는 요청과 응답 헤더에 같은 값을 실어 서비스 로그를 하나로 꿸 수 있게 한다.
 * - 라우트 전역 필터(GlobalFilter)가 아니라 WebFilter 라 라우트 매칭 전에, 그리고 게이트웨이 자체 API·404 에도 돈다.
 *   DotSegmentRejectFilter·StripClientIdentityFilter 등 GlobalFilter 는 모두 이 뒤라서 뒤 서비스가 받는 요청엔 항상 헤더가 있다.
 * - 사용자 id 는 AuthorizationHeaderFilter 가 JWT 를 검증한 뒤 exchange 속성(USER_ID_ATTR)에 남긴 값이다.
 *   클라이언트가 보낸 X-Auth-User-Id 는 StripClientIdentityFilter 가 지우므로 여기서 믿지 않는다.
 * - 로그를 찍는 스레드에서만 MDC(requestId, userId)를 넣었다 뺀다. 리액터 컨텍스트 전파는 하지 않는다.
 * - /actuator 아래 경로는 접근 로그를 남기지 않는다(probe·scrape 가 로그를 채우지 않게). 헤더는 붙는다.
 */
@Component
class RequestContextFilter : WebFilter, Ordered {

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val USER_ID_HEADER = AuthorizationHeaderFilter.USER_ID_HEADER

        /** AuthorizationHeaderFilter 가 검증한 사용자 id 를 두는 exchange 속성. */
        const val USER_ID_ATTR = "modu.userId"

        private const val MDC_REQUEST_ID = "requestId"
        private const val MDC_USER_ID = "userId"
        private val VALID_ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun isValidRequestId(id: String?): Boolean = id != null && VALID_ID.matches(id)

        fun newRequestId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

        fun resolveRequestId(given: String?): String = if (isValidRequestId(given)) given!! else newRequestId()
    }

    private val accessLog = LoggerFactory.getLogger("http.access")

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val started = System.nanoTime()
        val request = exchange.request
        val requestId = resolveRequestId(request.headers.getFirst(REQUEST_ID_HEADER))

        // 뒤 서비스로 가는 요청엔 항상 검증된 값 하나만 실린다(클라이언트가 보낸 잘못된 값은 여기서 바뀐다).
        val mutated = exchange.mutate()
            .request(request.mutate().headers { it.set(REQUEST_ID_HEADER, requestId) }.build())
            .build()

        // 응답 헤더는 커밋 직전에 set 으로 넣는다. 뒤 서비스도 같은 헤더를 돌려주는데(서비스 쪽 RequestContextFilter),
        // 프록시가 응답 헤더를 add 로 합치므로 미리 넣어 두면 같은 값이 두 번 실릴 수 있다.
        val response = exchange.response
        response.beforeCommit { Mono.fromRunnable { response.headers.set(REQUEST_ID_HEADER, requestId) } }

        var errorStatus: Int? = null
        return chain.filter(mutated)
            .doOnError { e -> errorStatus = (e as? ErrorResponse)?.statusCode?.value() ?: 500 }
            .doFinally {
                // 체인이 예외로 끝나면 상태는 아직 응답에 없다(예외 처리기가 뒤에 쓴다). 예외에서 상태를 읽는다.
                val status = response.statusCode?.value() ?: errorStatus ?: 500
                logAccess(exchange, requestId, status, (System.nanoTime() - started) / 1_000_000)
            }
    }

    private fun logAccess(exchange: ServerWebExchange, requestId: String, status: Int, durationMs: Long) {
        val path = exchange.request.path.value()
        if (path.startsWith("/actuator")) return
        val method = exchange.request.method.name()
        val userId = exchange.getAttribute<String>(USER_ID_ATTR)
        val routeId = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)?.id

        MDC.put(MDC_REQUEST_ID, requestId)
        if (userId != null) MDC.put(MDC_USER_ID, userId)
        try {
            // v(): JSON 엔 필드로, 메시지엔 값만("GET /path 200 12ms"). kv() 는 메시지에 key=value 로 찍힌다.
            accessLog.info(
                "{} {} {} {}ms",
                v("method", method),
                v("path", path),
                v("status", status),
                v("durationMs", durationMs),
                kv("event", "http.access"),
                kv("route", routeId),
            )
        } finally {
            MDC.remove(MDC_REQUEST_ID)
            if (userId != null) MDC.remove(MDC_USER_ID)
        }
    }

    /** 보안 필터 체인(-100)·GlobalFilter 보다 먼저. 모든 요청이 요청 id 를 갖고 시작한다. */
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
}
