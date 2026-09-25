package com.example.gatewayservice.jwt

import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import reactor.core.publisher.Mono

/**
 * 게이트웨이의 토큰 규칙 한 벌. 라우트의 AuthorizationHeaderFilter 와 게이트웨이 자체 관리 API(GatewayConfigController)가 같이 쓴다.
 * 서명·만료·발급자는 디코더(JWKS)가, 어느 앱의 토큰인지(audience)와 역할(roles)은 여기서 본다.
 */
object JwtAccess {
    const val BEARER = "Bearer "

    /** 통과하지 못한 이유. 호출부가 401 로 바꾼다. */
    class Denied(message: String) : RuntimeException(message)

    /** 통과하면 Jwt, 아니면 [Denied] 로 끝나는 Mono. [audience] 를 비우면 어느 앱의 토큰이든 받는다. */
    fun verify(decoder: ReactiveJwtDecoder, authorization: String?, role: String, audience: String?): Mono<Jwt> {
        if (authorization == null || !authorization.startsWith(BEARER)) return Mono.error(Denied("No authorization header"))
        return decoder.decode(authorization.substring(BEARER.length))
            .onErrorMap(JwtException::class.java) { Denied("JWT token is not valid: ${it.message}") }
            .flatMap { jwt ->
                when {
                    !audience.isNullOrBlank() && jwt.audience?.contains(audience) != true ->
                        Mono.error(Denied("JWT audience mismatch: requires $audience"))
                    jwt.getClaimAsStringList("roles")?.contains(role) != true ->
                        Mono.error(Denied("JWT role mismatch: requires $role"))
                    else -> Mono.just(jwt)
                }
            }
    }
}
