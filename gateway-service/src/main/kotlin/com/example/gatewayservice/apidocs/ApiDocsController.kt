package com.example.gatewayservice.apidocs

import com.example.gatewayservice.admin.GatewayConfigController.Companion.CONSOLE_AUDIENCE
import com.example.gatewayservice.admin.GatewayConfigController.Companion.SYSTEM_ROLE
import com.example.gatewayservice.jwt.JwtAccess
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * 시스템 콘솔의 'API 문서' 화면용. GatewayConfigController 와 같은 게이트웨이 자체 관리 API 라
 * 같은 `/gateway-service/api-admin` 아래 두고, 같은 규칙(ROLE_SYSTEM, aud=modu-admin, 아니면 본문 없는 401)으로 막는다.
 * 컨트롤러가 라우트보다 먼저 매칭되고, 이 경로를 잡는 라우트도 없다(GatewayRoutesTest).
 */
@RestController
@RequestMapping("/gateway-service/api-admin/api-docs")
class ApiDocsController(
    private val jwtDecoder: ReactiveJwtDecoder,
    private val apiDocsService: ApiDocsService,
) {
    private val log = LoggerFactory.getLogger(ApiDocsController::class.java)

    /** 문서를 볼 수 있는 서비스 목록. */
    @GetMapping
    fun services(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): Mono<ResponseEntity<Any>> =
        authorized(authorization) { Mono.just(ResponseEntity.ok(apiDocsService.services())) }

    /** 서비스 하나의 OpenAPI 문서. servers 는 게이트웨이 경유 주소로 바뀌어 있다. */
    @GetMapping("/{name}")
    fun docs(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable name: String,
    ): Mono<ResponseEntity<Any>> =
        authorized(authorization) {
            apiDocsService.docs(name)
                .map<ResponseEntity<Any>> { ResponseEntity.ok(it) }
                .onErrorResume(UnknownServiceException::class.java) { e ->
                    Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(MessageResponse(e.message.orEmpty())))
                }
                .onErrorResume(ApiDocsFetchException::class.java) { e ->
                    log.warn("api docs fetch failed: {} ({})", e.message, e.cause?.toString())
                    Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(MessageResponse(e.message.orEmpty())))
                }
        }

    private fun authorized(authorization: String?, body: () -> Mono<ResponseEntity<Any>>): Mono<ResponseEntity<Any>> =
        JwtAccess.verify(jwtDecoder, authorization, SYSTEM_ROLE, CONSOLE_AUDIENCE)
            .flatMap { body() }
            .onErrorResume(JwtAccess.Denied::class.java) { e ->
                log.warn("api docs denied: {}", e.message)
                Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build())
            }
}

data class MessageResponse(val message: String)
