package com.example.gatewayservice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono

/** 라우트 정의가 계층 규칙을 지키는지: public 만 통과, internal/debug 는 어떤 라우트에도 안 잡힌다. */
@SpringBootTest(
    properties = [
        "modu.internal-api.token=test-internal-token",
        "modu.oauth.jwks-uri=http://localhost:9900/oauth2/jwks",
        "modu.oauth.issuer=http://localhost:8000/auth-service",
    ],
)
@AutoConfigureWebTestClient
class GatewayRoutesTest {

    @Autowired
    lateinit var routeLocator: RouteLocator

    @Autowired
    lateinit var client: WebTestClient

    @MockitoBean
    lateinit var decoder: ReactiveJwtDecoder

    private fun jwt(aud: String, vararg roles: String): Jwt =
        Jwt.withTokenValue("t").header("alg", "RS256").subject("7").audience(listOf(aud)).claim("roles", roles.toList()).build()

    @BeforeEach
    fun tokens() {
        Mockito.`when`(decoder.decode("commerce-user")).thenReturn(Mono.just(jwt("modu-commerce", "ROLE_USER")))
        Mockito.`when`(decoder.decode("chat-user")).thenReturn(Mono.just(jwt("modu-chat", "ROLE_USER")))
        Mockito.`when`(decoder.decode("broken")).thenReturn(Mono.error(BadJwtException("bad signature")))
    }

    private fun call(method: HttpMethod, path: String, token: String?) =
        client.method(method).uri(path)
            .apply { if (token != null) headers { it.setBearerAuth(token) } }
            .exchange()

    private fun firstMatch(method: HttpMethod, path: String): Route? {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build())
        val routes = routeLocator.routes.collectList().block() ?: emptyList()
        return routes.firstOrNull { Mono.from(it.predicate.apply(exchange)).block() == true }
    }

    private fun routeId(method: HttpMethod, path: String): String? = firstMatch(method, path)?.id

    private companion object {
        /** 테스트 설정(config/application.yml)의 modu.services.commerce-service. */
        const val COMMERCE = "http://localhost:11"
    }

    @Test
    fun internalTier_hasNoRoute() {
        assertNull(firstMatch(HttpMethod.GET, "/chat-service/api-internal/chat/1"))
        assertNull(firstMatch(HttpMethod.GET, "/member-service/api-internal/member/id/u1"))
        assertNull(firstMatch(HttpMethod.POST, "/point-service/api-internal/point/earn"))
    }

    @Test
    fun debugTier_hasNoRoute() {
        assertNull(firstMatch(HttpMethod.POST, "/push-service/api-debug/push/users"))
    }

    @Test
    fun publicTier_routesToService() {
        assertEquals("chat-service-public", routeId(HttpMethod.GET, "/chat-service/api-public/chat/1/rooms"))
        assertEquals("push-service-public", routeId(HttpMethod.PUT, "/push-service/api-public/push/u1/token"))
        assertEquals("point-service-public", routeId(HttpMethod.POST, "/point-service/api-public/point/me/checkin"))
    }

    @Test
    fun oauth2Endpoints_areNoAuthRoutes() {
        assertEquals("auth-service-oauth2", routeId(HttpMethod.POST, "/auth-service/oauth2/token"))
        assertEquals("auth-service-oauth2", routeId(HttpMethod.POST, "/auth-service/oauth2/revoke"))
        assertEquals("auth-service-oauth2", routeId(HttpMethod.GET, "/auth-service/oauth2/jwks"))
        assertEquals("auth-service-oauth2", routeId(HttpMethod.GET, "/auth-service/userinfo"))
        assertEquals("auth-service-oauth2", routeId(HttpMethod.GET, "/auth-service/.well-known/openid-configuration"))
    }

    @Test
    fun legacyLoginRoutes_areGone() {
        // 무검증 로그인·재발급·공개 signup·관리자 로그인은 사라졌다. signup 경로는 이제 JWT 가 필요한 public 라우트에 잡힌다.
        assertNotEquals("auth-service-login", routeId(HttpMethod.POST, "/auth-service/api-public/login"))
        assertNotEquals("auth-service-reissue", routeId(HttpMethod.POST, "/auth-service/api-public/auth/reissue"))
        assertNotEquals("auth-service-admin-login", routeId(HttpMethod.POST, "/auth-service/api-public/admin/login"))
        assertEquals("member-service-public", routeId(HttpMethod.POST, "/member-service/api-public/member/signup"))
    }

    private fun assertNotEquals(unexpected: String, actual: String?) {
        org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual)
    }

    @Test
    fun ssoCode_isChatProtectedRoute() {
        assertEquals("auth-service-public", routeId(HttpMethod.POST, "/auth-service/api-public/auth/sso-code"))
    }

    @Test
    fun oldTierlessPaths_haveNoRoute() {
        assertNull(firstMatch(HttpMethod.GET, "/chat-service/chat/1/rooms"))
        assertNull(firstMatch(HttpMethod.POST, "/auth-service/login"))
    }

    @Test
    fun adminTier_routesToAdminRoute() {
        assertEquals("member-service-admin", routeId(HttpMethod.GET, "/member-service/api-admin/member"))
        assertEquals("point-service-admin", routeId(HttpMethod.GET, "/point-service/api-admin/point/rules"))
        assertEquals("chat-service-admin", routeId(HttpMethod.GET, "/chat-service/api-admin/chat/rooms"))
        assertEquals("push-service-admin", routeId(HttpMethod.POST, "/push-service/api-admin/push/broadcast"))
        assertEquals("storage-service-admin", routeId(HttpMethod.GET, "/storage-service/api-admin/view/x.jpg"))
    }

    @Test
    fun staffConsole_routesSplitByTier() {
        assertEquals("member-service-staff", routeId(HttpMethod.GET, "/member-service/api-staff/member"))
        assertEquals("member-service-staff", routeId(HttpMethod.GET, "/member-service/api-staff/member/3"))
        assertEquals("member-service-super", routeId(HttpMethod.PUT, "/member-service/api-super/staff/3"))
        assertEquals("member-service-super", routeId(HttpMethod.GET, "/member-service/api-super/staff"))
    }

    @Test
    fun configAdmin_routesOnlyAdminPathsToConfigServer() {
        val route = firstMatch(HttpMethod.GET, "/config-service/api-admin/config-repo/files")!!
        assertEquals("config-service-admin", route.id)
        assertEquals(8888, route.uri.port)
        // 설정 서버의 서비스용 경로(/{name}/{profile})는 게이트웨이로 열지 않는다.
        assertNull(firstMatch(HttpMethod.GET, "/config-service/chat-service/default"))
    }

    @Test
    fun commerceAdmin_routesToCommerceServiceAddress() {
        val route = firstMatch(HttpMethod.POST, "/commerce-service/api-admin/v1/products")!!
        assertEquals("commerce-service-admin", route.id)
        assertEquals(COMMERCE, route.uri.toString()) // ${modu.services.commerce-service}
    }

    @Test
    fun everyRouteTargetsAModuServicesAddressOrTheConfigServer() {
        // 라우트 uri 는 전부 modu.services.* 주소(테스트 설정의 localhost:N)거나 config-service 주소다. 레지스트리 스킴은 없다.
        val routes = routeLocator.routes.collectList().block()!!
        assertEquals(true, routes.isNotEmpty())
        val allowed = (1..11).map { "http://localhost:$it" } + "http://localhost:8888"
        for (route in routes) {
            assertEquals(true, route.uri.toString() in allowed, "${route.id} → ${route.uri}")
        }
    }

    @Test
    fun wsRoute_targetsWsServiceAddressWithWebSocketMetadata() {
        val route = firstMatch(HttpMethod.GET, "/ws-service/modu-chat/chat")!!
        assertEquals("ws-service", route.id)
        assertEquals("http://localhost:5", route.uri.toString()) // ${modu.services.ws-service}; Upgrade 요청은 게이트웨이가 ws:// 로 바꾼다
        assertEquals(true, route.metadata["websocket"].toString().toBoolean())
    }

    @Test
    fun commercePublic_tiersGetIsOpenEverythingElseNeedsCommerceToken() {
        val open = firstMatch(HttpMethod.GET, "/commerce-service/api-public/v1/tiers")!!
        assertEquals("commerce-service-public-open", open.id)
        assertEquals(COMMERCE, open.uri.toString())
        // 등급표 GET 만 열린다. 같은 경로의 다른 메서드·하위 경로는 인증 라우트로 간다.
        assertEquals("commerce-service-public", routeId(HttpMethod.POST, "/commerce-service/api-public/v1/tiers"))
        assertEquals("commerce-service-public", routeId(HttpMethod.GET, "/commerce-service/api-public/v1/tiers/1"))
        assertEquals("commerce-service-public", routeId(HttpMethod.GET, "/commerce-service/api-public/v1/products"))
        assertEquals("commerce-service-public", routeId(HttpMethod.POST, "/commerce-service/api-public/v1/orders"))
        assertEquals(COMMERCE, firstMatch(HttpMethod.GET, "/commerce-service/api-public/v1/products")!!.uri.toString())
    }

    @Test
    fun commercePublic_withoutTokenOrWithChatTokenIs401() {
        for (token in listOf(null, "broken", "chat-user")) {
            call(HttpMethod.GET, "/commerce-service/api-public/v1/products", token).expectStatus().isUnauthorized
            call(HttpMethod.POST, "/commerce-service/api-public/v1/tiers", token).expectStatus().isUnauthorized
        }
    }

    @Test
    fun commercePublic_passesAuthAndReachesTheServiceAddress() {
        // 테스트의 서비스 주소(localhost:11)엔 아무것도 없으니 인증을 지나 연결에 실패하면 5xx 다(401 이 아니면 필터를 통과한 것).
        call(HttpMethod.GET, "/commerce-service/api-public/v1/tiers", null).expectStatus().is5xxServerError
        call(HttpMethod.GET, "/commerce-service/api-public/v1/products", "commerce-user").expectStatus().is5xxServerError
    }

    @Test
    fun commerceOldAppApi_hasNoRoute() {
        // /api/v1/** 는 /api-public/v1/** 로 바뀌었다. 옛 경로는 게이트웨이에 없다.
        assertNull(firstMatch(HttpMethod.GET, "/commerce-service/api/v1/products"))
    }

    @Test
    fun gatewayOwnActuator_hasNoRoute() {
        // 게이트웨이 자신의 probe(/actuator/health/**)는 어떤 라우트에도 잡히지 않아야 한다.
        assertNull(firstMatch(HttpMethod.GET, "/actuator/health"))
        assertNull(firstMatch(HttpMethod.GET, "/actuator/health/liveness"))
        assertNull(firstMatch(HttpMethod.GET, "/actuator/health/readiness"))
        assertNull(firstMatch(HttpMethod.GET, "/actuator/prometheus"))
    }

    @Test
    fun gatewayOwnAdminApi_hasNoRoute() {
        // 게이트웨이 자체 관리 API(GatewayConfigController, ApiDocsController)는 어떤 라우트에도 잡히지 않아야 컨트롤러가 받는다.
        assertNull(firstMatch(HttpMethod.GET, "/gateway-service/api-admin/config"))
        assertNull(firstMatch(HttpMethod.GET, "/gateway-service/api-admin/api-docs"))
        assertNull(firstMatch(HttpMethod.GET, "/gateway-service/api-admin/api-docs/member-service"))
    }
}
