package com.example.gatewayservice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono

/** 라우트 정의가 계층 규칙을 지키는지: public 만 통과, internal/debug 는 어떤 라우트에도 안 잡힌다. */
@SpringBootTest(
    properties = [
        "modu.internal-api.token=test-internal-token",
        "modu.oauth.jwks-uri=http://localhost:9900/oauth2/jwks",
        "modu.oauth.issuer=http://localhost:8000/auth-service",
    ],
)
class GatewayRoutesTest {

    @Autowired
    lateinit var routeLocator: RouteLocator

    private fun firstMatch(method: HttpMethod, path: String): Route? {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build())
        val routes = routeLocator.routes.collectList().block() ?: emptyList()
        return routes.firstOrNull { Mono.from(it.predicate.apply(exchange)).block() == true }
    }

    private fun routeId(method: HttpMethod, path: String): String? = firstMatch(method, path)?.id

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
    fun configAdmin_routesOnlyAdminPathsToConfigServer() {
        val route = firstMatch(HttpMethod.GET, "/config-service/api-admin/config-repo/files")!!
        assertEquals("config-service-admin", route.id)
        assertEquals(8888, route.uri.port)
        // 설정 서버의 서비스용 경로(/{name}/{profile})는 게이트웨이로 열지 않는다.
        assertNull(firstMatch(HttpMethod.GET, "/config-service/chat-service/default"))
    }

    @Test
    fun commerceAdmin_routesToCommerceServiceByContainerName() {
        val route = firstMatch(HttpMethod.POST, "/commerce-service/api-admin/v1/products")!!
        assertEquals("commerce-service-admin", route.id)
        // commerce-service 는 Eureka 에 없다. modu-infra 네트워크의 컨테이너 이름으로 간다.
        assertEquals("http://commerce-service:8200", route.uri.toString())
    }

    @Test
    fun commerceAppApi_isNotRoutedThroughGateway() {
        // 앱은 commerce-service(8200)를 직접 부른다. 게이트웨이에는 admin 계층만 연다.
        assertNull(firstMatch(HttpMethod.GET, "/commerce-service/api/v1/products"))
    }
}
