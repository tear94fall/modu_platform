package com.example.configservice.admin

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.config.server.environment.NativeEnvironmentProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import jakarta.servlet.FilterChain
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.util.Base64

/**
 * 관리 콘솔(모두 시스템)용 config-repo 조회. 읽기 전용이다.
 *
 * 게이트웨이 라우트 config-service-admin 이 관리자 JWT 를 확인한 뒤 내부 토큰을 붙여 넘긴다.
 * 이 경로는 설정 서버의 `/{name}/{profiles}/{label}` 과 모양이 같지만, 글자 그대로의 경로가 패턴보다 먼저 맞는다.
 */
@RestController
@RequestMapping("/api-admin/config-repo")
class ConfigRepoController(private val reader: ConfigRepoReader) {

    @GetMapping("/files")
    fun files(): List<ConfigFileSummary> = reader.files()

    @GetMapping("/file")
    fun file(@RequestParam path: String): ResponseEntity<ConfigFileView> =
        reader.read(path)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
}

/** 관리 경로(/api-admin 아래)와 암·복호화(/encrypt, /decrypt)는 X-Internal-Token 이 맞아야 연다. 토큰이 비어 있으면 모두 거절한다. */
/**
 * config-service 는 금고다. 설정 조회(`/{app}/{profile}`)가 열려 있으면 `{cipher}` 가 풀린 평문(DB 비밀번호·내부 토큰·외부 토큰)이
 * 인증 없이 나가고, `/decrypt`·`/encrypt` 는 암호문을 평문으로 바꿔 주며, `/actuator/refresh`·`busrefresh` 는 리로드를 트리거한다.
 * 그래서 기본은 '전부 잠금'이고 쿠버네티스가 부르는 상태 점검만 연다(헤더를 넣을 수 없는 경로).
 *
 * 인터셉터가 아니라 필터인 이유: `addInterceptors` 는 액추에이터의 핸들러 매핑에는 걸리지 않아 `/actuator/refresh` 가 그대로 열린다.
 *
 * 받는 자격 증명 두 가지:
 * - `X-Internal-Token: <토큰>` — 운영 도구·콘솔.
 * - HTTP Basic 의 비밀번호 — 각 서비스의 Spring Cloud Config 클라이언트(`spring.cloud.config.password`). 사용자 이름은 보지 않는다.
 *   클라이언트는 설정을 받기 전이라 토큰을 config-repo 가 아니라 환경변수(k8s Secret)로 받아야 한다.
 */
class ConfigServerAuthFilter(token: String) : OncePerRequestFilter() {
    private val expected = token.toByteArray()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path == "/actuator/info" || path == "/actuator/health" || path.startsWith("/actuator/health/")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (expected.isNotEmpty() && (matches(request.getHeader(HEADER)) || matches(basicPassword(request)))) {
            chain.doFilter(request, response)
            return
        }
        response.status = HttpServletResponse.SC_UNAUTHORIZED
    }

    private fun matches(given: String?): Boolean =
        given != null && MessageDigest.isEqual(expected, given.toByteArray())

    private fun basicPassword(request: HttpServletRequest): String? {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        if (!header.startsWith(BASIC_PREFIX, ignoreCase = true)) return null
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(BASIC_PREFIX.length).trim())) }.getOrNull() ?: return null
        return decoded.substringAfter(':', missingDelimiterValue = "").ifEmpty { null }
    }

    companion object {
        const val HEADER = "X-Internal-Token"
        private const val BASIC_PREFIX = "Basic "
    }
}

@Configuration
class ConfigRepoAdminConfig(
    @Value("\${modu.internal-api.token:}") private val internalToken: String,
) {

    @Bean
    fun configRepoReader(native: NativeEnvironmentProperties): ConfigRepoReader =
        ConfigRepoReader.fromLocations(native.searchLocations)

    /** 상태 점검을 뺀 모든 경로를 잠근다. RequestContextFilter(MDC) 다음에 돈다. */
    @Bean
    fun configServerAuthFilter(): FilterRegistrationBean<ConfigServerAuthFilter> =
        FilterRegistrationBean(ConfigServerAuthFilter(internalToken)).apply {
            addUrlPatterns("/*")
            order = Ordered.HIGHEST_PRECEDENCE + 10
        }
}

