package com.example.configservice.admin

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.config.server.environment.NativeEnvironmentProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.security.MessageDigest

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

/** 관리 경로(/api-admin 아래)는 X-Internal-Token 이 맞아야 연다. 토큰이 비어 있으면 모두 거절한다. */
class InternalTokenInterceptor(token: String) : HandlerInterceptor {
    private val expected = token.toByteArray()

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val given = request.getHeader(HEADER)?.toByteArray()
        if (expected.isNotEmpty() && given != null && MessageDigest.isEqual(expected, given)) return true
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        return false
    }

    companion object {
        const val HEADER = "X-Internal-Token"
    }
}

@Configuration
class ConfigRepoAdminConfig(
    @Value("\${modu.internal-api.token:}") private val internalToken: String,
) : WebMvcConfigurer {

    @Bean
    fun configRepoReader(native: NativeEnvironmentProperties): ConfigRepoReader =
        ConfigRepoReader.fromLocations(native.searchLocations)

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(InternalTokenInterceptor(internalToken)).addPathPatterns("/api-admin/**")
    }
}
