package com.example.gatewayservice.filter

import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * JWKS 로 서명·만료·발급자를 검증하고, 라우트가 요구하는 role 과 audience(어느 앱의 토큰인지)를 확인한다.
 * 통과하면 X-Auth-User-Id(sub) 와 X-Auth-Client-Id(aud) 를 넣어 준다.
 */
@Component
class AuthorizationHeaderFilter(
    private val jwtDecoder: ReactiveJwtDecoder,
) : AbstractGatewayFilterFactory<AuthorizationHeaderFilter.Config>(Config::class.java) {

    companion object {
        const val USER_ID_HEADER = "X-Auth-User-Id"
        const val CLIENT_ID_HEADER = "X-Auth-Client-Id"
        private const val BEARER = "Bearer "
    }

    private val log = LoggerFactory.getLogger(AuthorizationHeaderFilter::class.java)

    /** yml 에서 `AuthorizationHeaderFilter=ROLE_ADMIN,modu-admin` 처럼 role, audience 순으로 받는다. */
    override fun shortcutFieldOrder(): List<String> = listOf("role", "audience")

    override fun apply(config: Config): GatewayFilter =
        GatewayFilter { exchange, chain ->
            val header = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
            if (header == null || !header.startsWith(BEARER)) {
                return@GatewayFilter onError(exchange, "No authorization header", HttpStatus.UNAUTHORIZED)
            }
            jwtDecoder.decode(header.substring(BEARER.length))
                .flatMap { jwt ->
                    val requiredAudience = config.audience
                    if (!requiredAudience.isNullOrBlank() && jwt.audience?.contains(requiredAudience) != true) {
                        return@flatMap onError(exchange, "JWT audience mismatch: requires $requiredAudience", HttpStatus.UNAUTHORIZED)
                    }
                    val roles = jwt.getClaimAsStringList("roles")
                    if (roles == null || !roles.contains(config.role)) {
                        return@flatMap onError(exchange, "JWT role mismatch: requires ${config.role}", HttpStatus.UNAUTHORIZED)
                    }
                    val userId = jwt.subject
                    val clientId = jwt.audience?.firstOrNull() ?: ""
                    val mutated = exchange.request.mutate().header(CLIENT_ID_HEADER, clientId)
                    if (!userId.isNullOrBlank()) {
                        mutated.header(USER_ID_HEADER, userId)
                    }
                    chain.filter(exchange.mutate().request(mutated.build()).build())
                }
                .onErrorResume(JwtException::class.java) { e ->
                    onError(exchange, "JWT token is not valid: ${e.message}", HttpStatus.UNAUTHORIZED)
                }
        }

    private fun onError(exchange: ServerWebExchange, err: String, httpStatus: HttpStatus): Mono<Void> {
        val response = exchange.response
        response.statusCode = httpStatus
        log.error(err)
        return response.setComplete()
    }

    /** 라우트 인자. 기본 role 은 ROLE_USER, audience 를 비우면 어느 앱의 토큰이든 받는다. */
    class Config {
        var role: String = "ROLE_USER"
        var audience: String? = null
    }
}
