package com.example.deployservice.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.StringUtils
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UrlPathHelper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * `/api-system/...` 는 게이트웨이 라우트 deploy-service-system 이 시스템 권한(ROLE_SYSTEM) JWT 를 확인한 뒤
 * `modu.internal-api.token` 을 X-Internal-Token 으로 붙여 넘긴다(config-service 의 InternalTokenInterceptor 와 같은 규칙).
 * 서비스 포트로 직접 오는 요청은 막을 수 없으므로 여기서 토큰을 검사하고, 아니면 401 `{error, message}` 로 답한다.
 *
 * 경로 비교는 Spring 이 핸들러 매핑에 쓰는 것과 같은 디코딩·정규화된 경로로 한다(//api-system, /%61pi-system, /api-system;x=1 도 잡는다).
 * 빈 토큰은 누구나 통과시키므로 기동을 거부한다(config-repo 의 application.yml 이 토큰을 내려준다).
 */
@Component
class InternalTokenFilter(@Value("\${modu.internal-api.token}") expectedToken: String) : OncePerRequestFilter() {

    private val expected: ByteArray

    init {
        if (!StringUtils.hasText(expectedToken)) {
            throw IllegalStateException("modu.internal-api.token 이 비어 있다. 빈 토큰은 누구나 통과시키므로 기동을 거부한다.")
        }
        expected = expectedToken.toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        const val HEADER = "X-Internal-Token"
        const val GUARDED_PREFIX = "/api-system/"
        private val PATH_HELPER = UrlPathHelper()

        /** 디코딩하고 ;파라미터를 떼고 // 와 .. 을 정리한 경로. */
        internal fun normalizedPath(request: HttpServletRequest): String {
            val path = PATH_HELPER.getPathWithinApplication(request)
            return StringUtils.cleanPath(path.replace(Regex("/{2,}"), "/"))
        }

        internal fun isGuarded(normalizedPath: String): Boolean =
            normalizedPath.startsWith(GUARDED_PREFIX) || normalizedPath == "/api-system"
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !isGuarded(normalizedPath(request))

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val given = request.getHeader(HEADER)
        if (given == null || !MessageDigest.isEqual(given.toByteArray(StandardCharsets.UTF_8), expected)) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = StandardCharsets.UTF_8.name()
            response.writer.write("""{"error":"unauthorized","message":"내부 토큰(X-Internal-Token)이 필요합니다."}""")
            return
        }
        chain.doFilter(request, response)
    }
}
