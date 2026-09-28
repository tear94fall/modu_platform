package com.example.gatewayservice

import com.example.gatewayservice.apidocs.ApiDocsFetcher
import com.example.gatewayservice.apidocs.ApiDocsService
import com.example.gatewayservice.apidocs.LoadBalancedApiDocsFetcher
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

/** API 문서: 시스템 권한 토큰(ROLE_SYSTEM, aud=modu-admin)만 통과하고, 목록은 라우트의 lb:// 대상, 문서는 servers 를 게이트웨이 경유로 바꿔 준다. */
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
                    "auth-service", "chat-service", "chat-store-service", "commerce-service", "member-service", "point-service",
                    "profile-service", "push-service", "schedule-service", "storage-service", "ws-service",
                ),
            )
            .jsonPath("$.services[0].title").isEqualTo("auth-service")
            .jsonPath("$.services[?(@.name == 'member-service')].routed").isEqualTo(true)
            .jsonPath("$.services[?(@.name == 'commerce-service')].routed").isEqualTo(true)
            .jsonPath("$.services[?(@.name == 'ws-service')].routed").isEqualTo(false) // WebSocket 라우트뿐
            .jsonPath("$.services[?(@.name == 'chat-store-service')].routed").isEqualTo(false)
            .jsonPath("$.services[?(@.name == 'schedule-service')].routed").isEqualTo(false)
    }

    @Test
    fun routedIgnoresWebSocketRoutes() {
        fun route(uri: String, path: String) = org.springframework.cloud.gateway.route.RouteDefinition().apply {
            this.uri = URI.create(uri)
            predicates = listOf(org.springframework.cloud.gateway.handler.predicate.PredicateDefinition("Path=$path"))
        }
        assertEquals(false, ApiDocsService.isRouted(listOf(route("lb:ws://WS-SERVICE", "/ws-service/modu-chat/**")), "ws-service"))
        assertEquals(false, ApiDocsService.isRouted(listOf(route("ws://ws-service:8080", "/ws-service/**")), "ws-service"))
        assertEquals(true, ApiDocsService.isRouted(listOf(route("lb://WS-SERVICE", "/ws-service/api-admin/**")), "ws-service"))
        assertEquals(true, ApiDocsService.isRouted(listOf(route("http://commerce-service:8200", "/commerce-service/api-admin/**")), "commerce-service"))
        assertEquals(true, ApiDocsService.isWebSocket(URI.create("lb:wss://X")))
        assertEquals(false, ApiDocsService.isWebSocket(URI.create("lb://X")))
    }

    @Test
    fun targetsUseLbForAllServicesIncludingCommerce() {
        val targets = context.getBean(ApiDocsService::class.java).targets().associate { it.name to it.baseUri.toString() }
        assertEquals("lb://member-service", targets["member-service"])
        assertEquals("lb://ws-service", targets["ws-service"]) // 라우트는 lb:ws://WS-SERVICE, 문서는 http
        assertEquals("lb://chat-store-service", targets["chat-store-service"])
        assertEquals("lb://schedule-service", targets["schedule-service"])
        // commerce-service 는 extra-services 가 아니라 라우트(lb://COMMERCE-SERVICE)에서 잡힌다.
        assertEquals("lb://commerce-service", targets["commerce-service"])
        assertEquals(false, targets.keys.any { it in setOf("gateway-service", "config-service", "discovery-service") })
    }

    @Test
    fun docsOfRouteAndExtraServicesAreFetchedThroughEureka() {
        Mockito.`when`(fetcher.fetch("commerce-service", URI.create("lb://commerce-service")))
            .thenReturn(Mono.just(mapper.readTree("""{"openapi":"3.0.1","servers":[{"url":"http://commerce-service:8200"}]}""")))
        Mockito.`when`(fetcher.fetch("chat-store-service", URI.create("lb://chat-store-service")))
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
        Mockito.`when`(fetcher.fetch("member-service", URI.create("lb://member-service"))).thenReturn(Mono.just(doc))

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
        Mockito.`when`(fetcher.fetch("point-service", URI.create("lb://point-service"))).thenReturn(Mono.just(mapper.readTree("""{"openapi":"3.0.1"}""")))
        get("/point-service", "system").expectStatus().isOk.expectBody()
            .jsonPath("$.servers[0].url").isEqualTo("/point-service")
    }

    @Test
    fun unknownOrExcludedServiceIs404WithoutFetching() {
        for (name in listOf("nope-service", "gateway-service", "config-service", "discovery-service", "MEMBER-SERVICE")) {
            get("/$name", "system").expectStatus().isNotFound.expectBody()
                .jsonPath("$.message").isNotEmpty
        }
        Mockito.verifyNoInteractions(fetcher)
    }

    @Test
    fun fetchFailureTimeoutOrNonObjectIs502() {
        Mockito.`when`(fetcher.fetch("chat-service", URI.create("lb://chat-service"))).thenReturn(Mono.error(IllegalStateException("connection refused")))
        Mockito.`when`(fetcher.fetch("push-service", URI.create("lb://push-service"))).thenReturn(Mono.error(TimeoutException("5s")))
        Mockito.`when`(fetcher.fetch("storage-service", URI.create("lb://storage-service"))).thenReturn(Mono.just(mapper.readTree("[1,2]")))
        Mockito.`when`(fetcher.fetch("auth-service", URI.create("lb://auth-service"))).thenReturn(Mono.empty())

        for (name in listOf("chat-service", "push-service", "storage-service", "auth-service")) {
            get("/$name", "system").expectStatus().isEqualTo(502).expectBody()
                .jsonPath("$.message").isEqualTo("$name 문서를 불러오지 못했습니다")
        }
    }

    @Test
    fun loadBalancedBuilderDoesNotReplaceTheDefaultWebClientBuilder() {
        // @LoadBalanced 빌더는 defaultCandidate=false 라 부트의 기본 WebClient.Builder 는 그대로 있다.
        assertEquals(2, context.getBeanNamesForType(WebClient.Builder::class.java).size)
        assertEquals(true, context.getBeansOfType(ApiDocsService::class.java).isNotEmpty())
        assertEquals(0, context.getBeansOfType(LoadBalancedApiDocsFetcher::class.java).size) // 테스트에선 mock 으로 바꿔 끼웠다
    }
}
