package com.example.gatewayservice

import com.example.gatewayservice.apidocs.ApiDocsFetcher
import com.example.gatewayservice.apidocs.ApiDocsService
import com.example.gatewayservice.apidocs.ServiceAddresses
import com.example.gatewayservice.apidocs.WebClientApiDocsFetcher
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.net.URI
import java.util.concurrent.TimeoutException

/** API 문서: 시스템 권한 토큰(ROLE_SYSTEM, aud=modu-admin)만 통과하고, 목록은 라우트 uri 가 가리키는 modu.services 서비스, 문서는 servers 를 게이트웨이 경유로 바꿔 준다. */
@SpringBootTest(
    properties = [
        "modu.internal-api.token=test-internal-token",
        "modu.oauth.jwks-uri=http://localhost:9900/oauth2/jwks",
        "modu.oauth.issuer=http://localhost:8000/auth-service",
    ],
)
@AutoConfigureWebTestClient
class ApiDocsControllerTest {

    @Autowired lateinit var client: WebTestClient
    @Autowired lateinit var context: ApplicationContext

    @MockitoBean lateinit var decoder: ReactiveJwtDecoder
    @MockitoBean lateinit var fetcher: ApiDocsFetcher

    private val mapper = ObjectMapper()

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

    private fun get(path: String, token: String?) =
        client.get().uri("/gateway-service/api-admin/api-docs$path")
            .apply { if (token != null) headers { it.setBearerAuth(token) } }
            .exchange()

    @Test
    fun rejectsMissingBrokenWrongAudienceAndWrongRole() {
        for (path in listOf("", "/member-service")) {
            get(path, null).expectStatus().isUnauthorized.expectBody().isEmpty
            get(path, "broken").expectStatus().isUnauthorized.expectBody().isEmpty
            get(path, "admin-wrong-aud").expectStatus().isUnauthorized
            get(path, "chat-user").expectStatus().isUnauthorized
            // 어드민·인터널 권한만 있는 직원은 API 문서를 못 본다.
            get(path, "admin-only").expectStatus().isUnauthorized
        }
        Mockito.verifyNoInteractions(fetcher)
    }

    @Test
    fun listsRouteServicesPlusExtraServicesSortedWithRouted() {
        get("", "system").expectStatus().isOk.expectBody()
            .jsonPath("$.services[*].name").isEqualTo(
                listOf(
                    "auth-service", "chat-service", "chat-store-service", "commerce-service", "deploy-service", "member-service",
                    "point-service", "profile-service", "push-service", "schedule-service", "storage-service", "ws-service",
                ),
            )
            .jsonPath("$.services[0].title").isEqualTo("auth-service")
            .jsonPath("$.services[?(@.name == 'member-service')].routed").isEqualTo(true)
            .jsonPath("$.services[?(@.name == 'commerce-service')].routed").isEqualTo(true)
            .jsonPath("$.services[?(@.name == 'deploy-service')].routed").isEqualTo(true) // deploy-service-system 라우트(${modu.services.deploy-service})
            .jsonPath("$.services[?(@.name == 'ws-service')].routed").isEqualTo(false) // WebSocket 라우트뿐
            .jsonPath("$.services[?(@.name == 'chat-store-service')].routed").isEqualTo(false)
            .jsonPath("$.services[?(@.name == 'schedule-service')].routed").isEqualTo(false)
    }

    @Test
    fun routedIgnoresWebSocketRoutes() {
        fun route(uri: String, path: String, websocket: Boolean = false) = org.springframework.cloud.gateway.route.RouteDefinition().apply {
            this.uri = URI.create(uri)
            predicates = listOf(org.springframework.cloud.gateway.handler.predicate.PredicateDefinition("Path=$path"))
            if (websocket) metadata = mapOf(ApiDocsService.WEBSOCKET_METADATA to true)
        }
        // 실제 설정: uri 는 http://(modu.services.ws-service) 지만 metadata websocket: true 라 HTTP 라우트로 세지 않는다.
        assertEquals(false, ApiDocsService.isRouted(listOf(route("http://ws-service:8090", "/ws-service/modu-chat/**", websocket = true)), "ws-service"))
        assertEquals(false, ApiDocsService.isRouted(listOf(route("ws://ws-service:8090", "/ws-service/**")), "ws-service"))
        assertEquals(true, ApiDocsService.isRouted(listOf(route("http://ws-service:8090", "/ws-service/api-admin/**")), "ws-service"))
        assertEquals(true, ApiDocsService.isRouted(listOf(route("http://commerce-service:8200", "/commerce-service/api-admin/**")), "commerce-service"))
        assertEquals(true, ApiDocsService.isWebSocket(route("wss://x:1", "/x/**")))
        assertEquals(true, ApiDocsService.isWebSocket(route("http://x:1", "/x/**", websocket = true)))
        assertEquals(false, ApiDocsService.isWebSocket(route("http://x:1", "/x/**")))
    }

    @Test
    fun targetsAreModuServicesAddressesOfRoutesAndExtraServices() {
        val targets = context.getBean(ApiDocsService::class.java).targets().associate { it.name to it.baseUri.toString() }
        assertEquals("http://localhost:2", targets["member-service"])
        assertEquals("http://localhost:5", targets["ws-service"]) // WebSocket 라우트의 주소로 http 문서를 받는다
        assertEquals("http://localhost:4", targets["chat-store-service"]) // extra-services → ${modu.services.chat-store-service}
        assertEquals("http://localhost:10", targets["schedule-service"])
        // commerce-service 는 extra-services 가 아니라 라우트(${modu.services.commerce-service})에서 잡힌다.
        assertEquals("http://localhost:11", targets["commerce-service"])
        // config-service 라우트(http://localhost:8888)는 modu.services 에 없으니 목록에 없다. 게이트웨이 자신도.
        assertEquals(false, targets.keys.any { it in setOf("gateway-service", "config-service", "localhost") })
    }

    @Test
    fun serviceAddresses_nameOfMatchesSchemeHostPortCaseInsensitively() {
        val addresses = ServiceAddresses(mapOf("Member-Service" to URI.create("http://member-service:8080"), "ws-service" to URI.create("http://ws-service:8090")))
        assertEquals("member-service", addresses.nameOf(URI.create("HTTP://MEMBER-SERVICE:8080")))
        assertEquals("member-service", addresses.nameOf(URI.create("http://member-service:8080/")))
        assertEquals(null, addresses.nameOf(URI.create("http://member-service:8081")))
        assertEquals(null, addresses.nameOf(URI.create("https://member-service:8080")))
        assertEquals(null, addresses.nameOf(URI.create("http://config-service:8888")))
        assertEquals(null, addresses.nameOf(URI.create("lb:ws://WS-SERVICE")))
        assertEquals(null, addresses.nameOf(null))
    }

    @Test
    fun docsOfRouteAndExtraServicesAreFetchedFromTheirAddress() {
        Mockito.`when`(fetcher.fetch("commerce-service", URI.create("http://localhost:11")))
            .thenReturn(Mono.just(mapper.readTree("""{"openapi":"3.0.1","servers":[{"url":"http://commerce-service:8200"}]}""")))
        Mockito.`when`(fetcher.fetch("chat-store-service", URI.create("http://localhost:4")))
            .thenReturn(Mono.just(mapper.readTree("""{"openapi":"3.0.1"}""")))

        get("/commerce-service", "system").expectStatus().isOk.expectBody()
            .jsonPath("$.servers.length()").isEqualTo(1)
            .jsonPath("$.servers[0].url").isEqualTo("/commerce-service")
        get("/chat-store-service", "system").expectStatus().isOk.expectBody()
            .jsonPath("$.servers[0].url").isEqualTo("/chat-store-service")
    }

    @Test
    fun docsReplaceServersWithGatewayPath() {
        val doc = mapper.readTree(
            """{"openapi":"3.0.1","info":{"title":"member"},"servers":[{"url":"http://172.18.0.5:8080","description":"Generated server url"}],"paths":{"/api-public/member/me":{}}}""",
        )
        Mockito.`when`(fetcher.fetch("member-service", URI.create("http://localhost:2"))).thenReturn(Mono.just(doc))

        get("/member-service", "system").expectStatus().isOk.expectBody()
            .jsonPath("$.openapi").isEqualTo("3.0.1")
            .jsonPath("$.info.title").isEqualTo("member")
            .jsonPath("$.paths['/api-public/member/me']").exists()
            .jsonPath("$.servers.length()").isEqualTo(1)
            .jsonPath("$.servers[0].url").isEqualTo("/member-service")
            .jsonPath("$.servers[0].description").isEqualTo("게이트웨이 경유")
    }

    @Test
    fun docsAddServersWhenMissing() {
        Mockito.`when`(fetcher.fetch("point-service", URI.create("http://localhost:9"))).thenReturn(Mono.just(mapper.readTree("""{"openapi":"3.0.1"}""")))
        get("/point-service", "system").expectStatus().isOk.expectBody()
            .jsonPath("$.servers[0].url").isEqualTo("/point-service")
    }

    @Test
    fun unknownOrExcludedServiceIs404WithoutFetching() {
        for (name in listOf("nope-service", "gateway-service", "config-service", "localhost", "MEMBER-SERVICE")) {
            get("/$name", "system").expectStatus().isNotFound.expectBody()
                .jsonPath("$.message").isNotEmpty
        }
        Mockito.verifyNoInteractions(fetcher)
    }

    @Test
    fun fetchFailureTimeoutOrNonObjectIs502() {
        Mockito.`when`(fetcher.fetch("chat-service", URI.create("http://localhost:3"))).thenReturn(Mono.error(IllegalStateException("connection refused")))
        Mockito.`when`(fetcher.fetch("push-service", URI.create("http://localhost:6"))).thenReturn(Mono.error(TimeoutException("5s")))
        Mockito.`when`(fetcher.fetch("storage-service", URI.create("http://localhost:7"))).thenReturn(Mono.just(mapper.readTree("[1,2]")))
        Mockito.`when`(fetcher.fetch("auth-service", URI.create("http://localhost:1"))).thenReturn(Mono.empty())

        for (name in listOf("chat-service", "push-service", "storage-service", "auth-service")) {
            get("/$name", "system").expectStatus().isEqualTo(502).expectBody()
                .jsonPath("$.message").isEqualTo("$name 문서를 불러오지 못했습니다")
        }
    }

    @Test
    fun fetcherUsesTheOnlyPlainWebClientBuilder() {
        // 로드밸런서용 빌더는 없다. 부트의 기본 WebClient.Builder 하나뿐이고, 문서 조회는 그걸로 호스트 이름을 그대로 부른다.
        assertEquals(1, context.getBeanNamesForType(WebClient.Builder::class.java).size)
        assertEquals(true, context.getBeansOfType(ApiDocsService::class.java).isNotEmpty())
        assertEquals(0, context.getBeansOfType(WebClientApiDocsFetcher::class.java).size) // 테스트에선 mock 으로 바꿔 끼웠다
    }
}
