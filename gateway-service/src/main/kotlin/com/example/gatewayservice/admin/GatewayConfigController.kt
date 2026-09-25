package com.example.gatewayservice.admin

import com.example.gatewayservice.jwt.JwtAccess
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.config.GatewayProperties
import org.springframework.cloud.gateway.config.GlobalCorsProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * 게이트웨이 자신의 관리 API. 라우트가 아니라 게이트웨이 앱의 컨트롤러라서, 다른 서비스처럼
 * `/<서비스>/api-admin/...` 모양으로 둔다. 컨트롤러가 라우트보다 먼저 매칭되고, 이 경로를 잡는 라우트도 없다.
 * 라우트의 `AuthorizationHeaderFilter=ROLE_ADMIN,modu-admin` 과 같은 규칙으로 막는다(보안 체인은 여전히 permitAll).
 */
@RestController
@RequestMapping("/gateway-service/api-admin")
class GatewayConfigController(
    private val jwtDecoder: ReactiveJwtDecoder,
    private val gatewayProperties: GatewayProperties,
    private val globalCorsProperties: GlobalCorsProperties,
) {
    private val log = LoggerFactory.getLogger(GatewayConfigController::class.java)

    /** 라우트(설정 순서), 기본 필터, 전역 CORS. 읽기 전용이다. */
    @GetMapping("/config")
    fun config(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): Mono<ResponseEntity<GatewayConfigResponse>> =
        JwtAccess.verify(jwtDecoder, authorization, ADMIN_ROLE, ADMIN_AUDIENCE)
            .map { ResponseEntity.ok(GatewayConfigView.of(gatewayProperties, globalCorsProperties)) }
            .onErrorResume(JwtAccess.Denied::class.java) { e ->
                log.warn("gateway config denied: {}", e.message)
                Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build())
            }

    companion object {
        const val ADMIN_ROLE = "ROLE_ADMIN"
        const val ADMIN_AUDIENCE = "modu-admin"
    }
}
