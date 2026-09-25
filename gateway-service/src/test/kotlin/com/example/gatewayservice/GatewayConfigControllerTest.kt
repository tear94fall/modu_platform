package com.example.gatewayservice

import com.example.gatewayservice.admin.AccessType
import com.example.gatewayservice.admin.GatewayConfigView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono

/** 게이트웨이 설정 조회: 시스템 권한 토큰(ROLE_SYSTEM, aud=modu-admin)만 통과하고, 라우트·기본 필터·CORS 를 설정 그대로 보여 준다. */
@SpringBootTest(
    properties = [
        "modu.internal-api.token=test-internal-token",
        "modu.oauth.jwks-uri=http://localhost:9900/oauth2/jwks",
        "modu.oauth.issuer=http://localhost:8000/auth-service",
    ],
)
@AutoConfigureWebTestClient
class GatewayConfigControllerTest {

    @Autowired lateinit var client: WebTestClient

    @MockitoBean lateinit var decoder: ReactiveJwtDecoder

    private fun jwt(aud: String, vararg roles: String): Jwt =
        Jwt.withTokenValue("t").header("alg", "RS256").subject("admin").audience(listOf(aud)).claim("roles", roles.toList()).build()

    @BeforeEach
    fun tokens() {
        Mockito.`when`(decoder.decode("system")).thenReturn(Mono.just(jwt("modu-admin", "ROLE_SYSTEM")))
        Mockito.`when`(decoder.decode("admin-only")).thenReturn(Mono.just(jwt("modu-admin", "ROLE_ADMIN", "ROLE_INTERNAL")))
        Mockito.`when`(decoder.decode("chat-user")).thenReturn(Mono.just(jwt("modu-chat", "ROLE_USER")))
        Mockito.`when`(decoder.decode("admin-wrong-aud")).thenReturn(Mono.just(jwt("modu-chat", "ROLE_SYSTEM")))
        Mockito.`when`(decoder.decode("broken")).thenReturn(Mono.error(BadJwtException("bad signature")))
    }

    private fun get(token: String?) =
        client.get().uri("/gateway-service/api-admin/config")
            .apply { if (token != null) headers { it.setBearerAuth(token) } }
            .exchange()

    @Test
    fun rejectsMissingBrokenWrongAudienceAndWrongRole() {
        get(null).expectStatus().isUnauthorized.expectBody().isEmpty
        get("broken").expectStatus().isUnauthorized
        get("admin-wrong-aud").expectStatus().isUnauthorized
        get("chat-user").expectStatus().isUnauthorized
        // 어드민·인터널 권한만 있는 직원은 시스템 설정을 못 본다.
        get("admin-only").expectStatus().isUnauthorized
    }

    @Test
    fun adminSeesRoutesInOrderWithAccessDefaultFiltersAndCors() {
        get("system").expectStatus().isOk.expectBody()
            .jsonPath("$.routes[0].id").isEqualTo("auth-service-oauth2")
            .jsonPath("$.routes[0].uri").isEqualTo("lb://AUTH-SERVICE")
            .jsonPath("$.routes[0].access.type").isEqualTo("PUBLIC")
            .jsonPath("$.routes[0].access.role").doesNotExist()
            .jsonPath("$.routes[0].predicates[0].name").isEqualTo("Path")
            .jsonPath("$.routes[0].predicates[0].args[0]").isEqualTo("/auth-service/oauth2/token")
            .jsonPath("$.routes[0].predicates[0].args[1]").isEqualTo("/auth-service/oauth2/revoke")
            .jsonPath("$.routes[0].filters[0].name").isEqualTo("RewritePath")
            .jsonPath("$.routes[0].filters[0].args[0]").isEqualTo("/auth-service/(?<segment>.*)")
            .jsonPath("$.routes[0].filters[0].args[1]").isEqualTo("/\${segment}")
            .jsonPath("$.routes[?(@.id == 'point-service-admin')].access.type").isEqualTo("PROTECTED")
            .jsonPath("$.routes[?(@.id == 'point-service-admin')].access.role").isEqualTo("ROLE_ADMIN")
            .jsonPath("$.routes[?(@.id == 'point-service-admin')].access.audience").isEqualTo("modu-admin")
            .jsonPath("$.routes[?(@.id == 'chat-service-public')].access.audience").isEqualTo("modu-chat")
            .jsonPath("$.routes[?(@.id == 'config-service-admin')].access.role").isEqualTo("ROLE_SYSTEM")
            .jsonPath("$.routes[?(@.id == 'member-service-super')].access.role").isEqualTo("ROLE_SUPER")
            .jsonPath("$.routes[?(@.id == 'member-service-staff')].access.role").isEqualTo("ROLE_INTERNAL")
            .jsonPath("$.routes[?(@.id == 'config-service-admin')].filters[?(@.name == 'AddRequestHeader')].args[1]").isEqualTo("******")
            // 서비스 간 토큰 값은 절대 내보내지 않는다(설정값은 test-internal-token).
            .jsonPath("$.routes[?(@.id == 'point-service-admin')].filters[?(@.name == 'AddRequestHeader')].args[0]").isEqualTo("X-Internal-Token")
            .jsonPath("$.routes[?(@.id == 'point-service-admin')].filters[?(@.name == 'AddRequestHeader')].args[1]").isEqualTo("******")
            .jsonPath("$.defaultFilters[0].name").isEqualTo("RemoveRequestHeader")
            .jsonPath("$.defaultFilters[0].args[0]").isEqualTo("X-Internal-Token")
            .jsonPath("$.cors[0].pattern").isEqualTo("/**")
            .jsonPath("$.cors[0].allowCredentials").isEqualTo(false)
            .jsonPath("$.generatedAt").isNotEmpty
    }

    @Test
    fun responseNeverContainsTheInternalToken() {
        val body = get("system").expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody.orEmpty()
        assertEquals(false, body.contains("test-internal-token"))
    }

    @Test
    fun maskSecrets_hidesSecretHeaderValuesAndSecretKeys() {
        assertEquals(listOf("X-Internal-Token", "******"), GatewayConfigView.maskSecrets("AddRequestHeader", listOf("X-Internal-Token", "s3cr3t")))
        assertEquals(listOf("X-Request-Source", "gateway"), GatewayConfigView.maskSecrets("AddRequestHeader", listOf("X-Request-Source", "gateway")))
        assertEquals(listOf("password=******", "/a"), GatewayConfigView.maskSecrets("Anything", listOf("password=p", "/a")))
        assertEquals(listOf("X-Internal-Token"), GatewayConfigView.maskSecrets("RemoveRequestHeader", listOf("X-Internal-Token")))
    }

    @Test
    fun accessOf_readsShortcutAndNamedArgs() {
        assertEquals(AccessType.PUBLIC, GatewayConfigView.accessOf(null).type)
        val shortcut = GatewayConfigView.accessOf(mapOf("_genkey_0" to "ROLE_ADMIN", "_genkey_1" to "modu-admin"))
        assertEquals("ROLE_ADMIN" to "modu-admin", shortcut.role to shortcut.audience)
        val roleOnly = GatewayConfigView.accessOf(mapOf("_genkey_0" to "ROLE_USER"))
        assertEquals("ROLE_USER" to null, roleOnly.role to roleOnly.audience)
        val named = GatewayConfigView.accessOf(mapOf("audience" to "modu-chat"))
        assertEquals("ROLE_USER" to "modu-chat", named.role to named.audience)
        assertEquals(listOf("/a", "key=v", "/\${seg}"), GatewayConfigView.argsOf(linkedMapOf("_genkey_0" to "/a", "key" to "v", "_genkey_1" to "/\$\\{seg}")))
    }
}
