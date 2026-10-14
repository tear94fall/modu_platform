package com.example.configservice.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.logstash.logback.argument.StructuredArguments.kv
import net.logstash.logback.argument.StructuredArguments.v
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 요청마다 MDC 에 requestId·userId 를 넣고(모든 로그 줄에 실린다), 응답 헤더에 X-Request-Id 를 돌려주고,
 * 끝나면 접근 로그(http.access) 한 줄을 남긴다.
 *
 * - requestId: 게이트웨이가 붙인 X-Request-Id(글자·길이가 맞을 때만) 또는 새로 만든 16자.
 * - userId: 게이트웨이가 JWT 에서 꺼내 넣은 X-Auth-User-Id(있을 때만). 설정 서버의 관리 경로는 내부 토큰으로 열리므로 보통 없다.
 * - /actuator 아래(probe·scrape)는 접근 로그를 남기지 않는다.
 *
 * 빈 이름을 따로 준다: Spring Boot 가 같은 이름(requestContextFilter)으로 자기 RequestContextFilter 를 등록해서 이름이 겹친다.
 */
@Component("moduRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestContextFilter : OncePerRequestFilter() {

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val USER_ID_HEADER = "X-Auth-User-Id"
        const val MDC_REQUEST_ID = "requestId"
        const val MDC_USER_ID = "userId"
        private val VALID_ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun isValidRequestId(id: String?): Boolean = id != null && VALID_ID.matches(id)

        fun newRequestId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    }

    private val accessLog = LoggerFactory.getLogger("http.access")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val given = request.getHeader(REQUEST_ID_HEADER)
        val requestId = if (isValidRequestId(given)) given else newRequestId()
        val userId = request.getHeader(USER_ID_HEADER)?.takeIf { it.isNotBlank() }

        MDC.put(MDC_REQUEST_ID, requestId)
        if (userId != null) MDC.put(MDC_USER_ID, userId)
        response.setHeader(REQUEST_ID_HEADER, requestId)
        val started = System.nanoTime()
        try {
            chain.doFilter(request, response)
        } finally {
            val path = request.requestURI
            if (!path.startsWith("/actuator")) {
                // v(): JSON 엔 필드로, 메시지엔 값만("GET /path 200 12ms").
                accessLog.info(
                    "{} {} {} {}ms",
                    v("method", request.method),
                    v("path", path),
                    v("status", response.status),
                    v("durationMs", (System.nanoTime() - started) / 1_000_000),
                    kv("event", "http.access"),
                )
            }
            MDC.remove(MDC_REQUEST_ID)
            if (userId != null) MDC.remove(MDC_USER_ID)
        }
    }
}
