package com.example.gatewayservice.jwt

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder

/**
 * auth-service 의 JWKS(공개키)로 RS256 토큰을 검증한다. 공유 비밀키가 없으므로 게이트웨이는 토큰을 만들 수 없다.
 * Nimbus 가 JWKS 를 캐시해서 auth-service 가 잠시 내려가도 이미 받은 키로는 계속 검증된다.
 */
@Configuration
class JwtDecoderConfig {

    @Bean
    fun reactiveJwtDecoder(
        @Value("\${modu.oauth.jwks-uri}") jwksUri: String,
        @Value("\${modu.oauth.issuer}") issuer: String,
    ): ReactiveJwtDecoder {
        val decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build()
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator<Jwt>(JwtValidators.createDefaultWithIssuer(issuer)))
        return decoder
    }
}
