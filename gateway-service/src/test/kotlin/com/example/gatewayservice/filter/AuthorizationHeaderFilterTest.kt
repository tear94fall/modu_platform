package com.example.gatewayservice.filter

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Instant
import java.util.Date
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/** 테스트 키로 서명한 RS256 토큰으로 role/audience/만료 검사를 확인한다. JWKS 대신 공개키를 직접 준다. */
class AuthorizationHeaderFilterTest {

    companion object {
        const val ISSUER = "http://localhost:8000/auth-service"
        lateinit var key: RSAKey
        lateinit var decoder: ReactiveJwtDecoder

        @JvmStatic
        @BeforeAll
        fun keys() {
            key = RSAKeyGenerator(2048).keyID("test").generate()
            val d = NimbusReactiveJwtDecoder.withPublicKey(key.toRSAPublicKey()).build()
            d.setJwtValidator(DelegatingOAuth2TokenValidator<Jwt>(JwtValidators.createDefaultWithIssuer(ISSUER)))
            decoder = d
        }

        fun token(sub: String, roles: List<String>, aud: String, exp: Instant): String {
            val claims = JWTClaimsSet.Builder().subject(sub).issuer(ISSUER).audience(aud)
                .claim("roles", roles).issueTime(Date()).expirationTime(Date.from(exp)).build()
            val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims)
            jwt.sign(RSASSASigner(key))
            return jwt.serialize()
        }

        fun valid(aud: String, roles: List<String>): String = token("user-1", roles, aud, Instant.now().plusSeconds(600))
    }

    private val chain: GatewayFilterChain = mock(GatewayFilterChain::class.java)

    private fun exchange(authHeader: String?): MockServerWebExchange {
        val builder = MockServerHttpRequest.get("/x")
        if (authHeader != null) builder.header("Authorization", authHeader)
        return MockServerWebExchange.from(builder.build())
    }

    private fun filter(role: String, audience: String?): GatewayFilter {
        val c = AuthorizationHeaderFilter.Config()
        c.role = role
        c.audience = audience
        return AuthorizationHeaderFilter(decoder).apply(c)
    }

    @Test
    fun missingHeader_is401() {
        val ex = exchange(null)
        filter("ROLE_USER", "modu-chat").filter(ex, chain).block()
        assertEquals(HttpStatus.UNAUTHORIZED, ex.response.statusCode)
    }

    @Test
    fun garbageToken_is401() {
        val ex = exchange("Bearer not-a-jwt")
        filter("ROLE_USER", "modu-chat").filter(ex, chain).block()
        assertEquals(HttpStatus.UNAUTHORIZED, ex.response.statusCode)
    }

    @Test
    fun expiredToken_is401() {
        val ex = exchange("Bearer " + token("user-1", listOf("ROLE_USER"), "modu-chat", Instant.now().minusSeconds(600)))
        filter("ROLE_USER", "modu-chat").filter(ex, chain).block()
        assertEquals(HttpStatus.UNAUTHORIZED, ex.response.statusCode)
    }

    @Test
    fun audienceMismatch_is401() {
        val ex = exchange("Bearer " + valid("modu-commerce", listOf("ROLE_USER")))
        filter("ROLE_USER", "modu-chat").filter(ex, chain).block()
        assertEquals(HttpStatus.UNAUTHORIZED, ex.response.statusCode)
    }

    @Test
    fun roleMismatch_is401() {
        val ex = exchange("Bearer " + valid("modu-admin", listOf("ROLE_USER")))
        filter("ROLE_ADMIN", "modu-admin").filter(ex, chain).block()
        assertEquals(HttpStatus.UNAUTHORIZED, ex.response.statusCode)
    }

    @Test
    fun match_passesWithIdentityHeaders() {
        `when`(chain.filter(any())).thenReturn(Mono.empty())
        val ex = exchange("Bearer " + valid("modu-chat", listOf("ROLE_USER")))
        filter("ROLE_USER", "modu-chat").filter(ex, chain).block()
        assertNull(ex.response.statusCode)
        val captor = ArgumentCaptor.forClass(ServerWebExchange::class.java)
        verify(chain).filter(captor.capture())
        assertEquals("user-1", captor.value.request.headers.getFirst("X-Auth-User-Id"))
        assertEquals("modu-chat", captor.value.request.headers.getFirst("X-Auth-Client-Id"))
    }

    @Test
    fun forgedIdentityHeaders_areReplacedNotAppended() {
        `when`(chain.filter(any())).thenReturn(Mono.empty())
        val request = MockServerHttpRequest.get("/x")
            .header("Authorization", "Bearer " + valid("modu-chat", listOf("ROLE_USER")))
            .header("X-Auth-User-Id", "attacker").header("X-Auth-Client-Id", "modu-admin").build()
        val ex = MockServerWebExchange.from(request)
        filter("ROLE_USER", "modu-chat").filter(ex, chain).block()
        val captor = ArgumentCaptor.forClass(ServerWebExchange::class.java)
        verify(chain).filter(captor.capture())
        assertEquals(listOf("user-1"), captor.value.request.headers["X-Auth-User-Id"])
        assertEquals(listOf("modu-chat"), captor.value.request.headers["X-Auth-Client-Id"])
    }

    @Test
    fun noAudienceConfigured_acceptsAnyClient() {
        `when`(chain.filter(any())).thenReturn(Mono.empty())
        val ex = exchange("Bearer " + valid("modu-commerce", listOf("ROLE_USER")))
        filter("ROLE_USER", null).filter(ex, chain).block()
        assertNull(ex.response.statusCode)
    }

    @Test
    fun defaultRole_isUser() {
        val c = AuthorizationHeaderFilter.Config()
        assertEquals("ROLE_USER", c.role)
        assertNull(c.audience)
    }
}
