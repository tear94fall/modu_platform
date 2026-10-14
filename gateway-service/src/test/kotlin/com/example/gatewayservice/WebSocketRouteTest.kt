package com.example.gatewayservice

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * ws-service 라우트의 uri 는 `${modu.services.ws-service}`(http://) 다. Upgrade: websocket 요청이 오면 게이트웨이의
 * WebsocketRoutingFilter 가 스킴을 ws:// 로 바꿔 프록시하는지, 실제 서버를 띄워 확인한다(뒤 서비스 자리는 MockWebServer 의 에코 소켓).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "modu.internal-api.token=test-internal-token",
        "modu.oauth.jwks-uri=http://localhost:9900/oauth2/jwks",
        "modu.oauth.issuer=http://localhost:8000/auth-service",
    ],
)
class WebSocketRouteTest {

    @LocalServerPort
    var port: Int = 0

    @MockitoBean
    lateinit var decoder: ReactiveJwtDecoder

    @BeforeEach
    fun tokens() {
        val jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("7").audience(listOf("modu-chat")).claim("roles", listOf("ROLE_USER")).build()
        Mockito.`when`(decoder.decode("chat-user")).thenReturn(Mono.just(jwt))
    }

    @Test
    fun upgradeRequestIsProxiedAsWebSocketToTheHttpServiceAddress() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send("echo:$text")
                        webSocket.close(1000, "done") // 열어 두면 MockWebServer 가 못 내려간다
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
        val headers = HttpHeaders().apply { setBearerAuth("chat-user") }
        var received: String? = null

        ReactorNettyWebSocketClient().execute(URI.create("ws://localhost:$port/ws-service/modu-chat/chat"), headers) { session ->
            session.send(Mono.just(session.textMessage("ping")))
                .thenMany(session.receive().map { it.payloadAsText }.next().doOnNext { received = it })
                .then()
        }.block(Duration.ofSeconds(10))

        assertEquals("echo:ping", received)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/modu-chat/chat", request.path) // RewritePath 로 /ws-service 접두사가 빠졌다
        assertEquals("websocket", request.getHeader("Upgrade")?.lowercase())
        assertEquals("7", request.getHeader("X-Auth-User-Id")) // AuthorizationHeaderFilter 가 붙인 사용자 id
    }

    @Test
    fun upgradeWithoutTokenIs401BeforeReachingTheService() {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
            .get().uri("/ws-service/modu-chat/chat")
            .header(HttpHeaders.UPGRADE, "websocket")
            .header(HttpHeaders.CONNECTION, "Upgrade")
            .exchange()
            .expectStatus().isUnauthorized
        assertEquals(0, server.requestCount)
    }

    companion object {
        /** 뒤 서비스(ws-service) 자리. 주소가 http:// 라도 게이트웨이가 ws 로 프록시해야 한다. */
        private val server = MockWebServer().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun wsServiceAddress(registry: DynamicPropertyRegistry) {
            registry.add("modu.services.ws-service") { server.url("/").toString().trimEnd('/') }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.shutdown()
    }
}
