package com.example.gatewayservice

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

/**
 * 실제 서버를 띄워 X-Request-Id 가 끝에서 끝까지 가는지 본다: 응답 헤더에 실리고, 뒤 서비스(MockWebServer)가 같은 값을 받고,
 * 접근 로그에 요청 id 와 (인증 라우트면) 사용자 id 가 남는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "modu.internal-api.token=test-internal-token",
        "modu.oauth.jwks-uri=http://localhost:9900/oauth2/jwks",
        "modu.oauth.issuer=http://localhost:8000/auth-service",
    ],
)
class RequestIdPropagationTest {

    @LocalServerPort
    var port: Int = 0

    @MockitoBean
    lateinit var decoder: ReactiveJwtDecoder

    private val appender = ListAppender<ILoggingEvent>()
    private val accessLog = LoggerFactory.getLogger("http.access") as Logger

    private lateinit var client: WebTestClient

    @BeforeEach
    fun setUp() {
        val jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("7").audience(listOf("modu-chat")).claim("roles", listOf("ROLE_USER")).build()
        Mockito.`when`(decoder.decode("chat-user")).thenReturn(Mono.just(jwt))
        client = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
        appender.start()
        accessLog.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        accessLog.detachAppender(appender)
    }

    private fun accessEvent(): ILoggingEvent {
        // 접근 로그는 응답이 끝난 뒤 찍히므로 클라이언트가 응답을 받은 직후엔 아직 없을 수 있다
        for (i in 0 until 50) {
            appender.list.lastOrNull()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("no http.access event")
    }

    @Test
    fun responseCarriesGeneratedId_andDownstreamReceivesTheSameOne() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val response = client.get().uri("/commerce-service/api-public/v1/tiers").exchange()
            .expectStatus().isOk
            .expectHeader().exists("X-Request-Id")
            .returnResult(String::class.java)
        val requestId = response.responseHeaders.getFirst("X-Request-Id")!!
        assertEquals(16, requestId.length)
        assertEquals(1, response.responseHeaders["X-Request-Id"]!!.size)

        val downstream = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api-public/v1/tiers", downstream.path)
        assertEquals(listOf(requestId), downstream.headers.values("X-Request-Id"))

        val event = accessEvent()
        assertEquals(requestId, event.mdcPropertyMap["requestId"])
        assertEquals(false, event.mdcPropertyMap.containsKey("userId")) // 인증 없는 라우트
        assertEquals("GET /commerce-service/api-public/v1/tiers 200", event.formattedMessage.substringBeforeLast(" "))
        assertTrue(event.argumentArray.map { it.toString() }.contains("route=commerce-service-public-open"))
    }

    @Test
    fun providedValidId_isEchoedToDownstreamAndResponse_evenWhenDownstreamEchoesItToo() {
        // 서비스 쪽 RequestContextFilter 도 같은 헤더를 돌려준다. 응답엔 한 번만 실려야 한다.
        server.enqueue(MockResponse().setResponseCode(200).setHeader("X-Request-Id", "client-abc_123").setBody("{}"))

        client.get().uri("/chat-service/api-public/chat/1/rooms")
            .header("Authorization", "Bearer chat-user")
            .header("X-Request-Id", "client-abc_123")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("X-Request-Id", "client-abc_123")

        val downstream = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api-public/chat/1/rooms", downstream.path)
        assertEquals(listOf("client-abc_123"), downstream.headers.values("X-Request-Id"))
        assertEquals("7", downstream.getHeader("X-Auth-User-Id")) // 인증 필터의 사용자 id 는 그대로

        val event = accessEvent()
        assertEquals("client-abc_123", event.mdcPropertyMap["requestId"])
        assertEquals("7", event.mdcPropertyMap["userId"]) // JWT 의 sub
    }

    @Test
    fun providedInvalidId_isReplaced() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val bad = "bad id;" + "x".repeat(70)

        val response = client.get().uri("/commerce-service/api-public/v1/tiers")
            .header("X-Request-Id", bad)
            .exchange()
            .expectStatus().isOk
            .returnResult(String::class.java)
        val requestId = response.responseHeaders.getFirst("X-Request-Id")!!
        assertNotEquals(bad, requestId)
        assertTrue(Regex("[A-Za-z0-9_-]{1,64}").matches(requestId), requestId)

        val downstream = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(listOf(requestId), downstream.headers.values("X-Request-Id"))
    }

    @Test
    fun unauthorizedRequest_stillGetsIdAndIsLoggedWith401() {
        val before = server.requestCount
        client.get().uri("/chat-service/api-public/chat/1/rooms")
            .header("X-Request-Id", "no-token-1")
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().valueEquals("X-Request-Id", "no-token-1")
        assertEquals(before, server.requestCount) // 뒤 서비스엔 가지 않았다

        val event = accessEvent()
        assertEquals("no-token-1", event.mdcPropertyMap["requestId"])
        assertEquals("GET /chat-service/api-public/chat/1/rooms 401", event.formattedMessage.substringBeforeLast(" "))
    }

    @Test
    fun actuator_getsIdButNoAccessLog() {
        client.get().uri("/actuator/health/liveness").exchange()
            .expectStatus().isOk
            .expectHeader().exists("X-Request-Id")
        Thread.sleep(100)
        assertTrue(appender.list.isEmpty(), appender.list.joinToString { it.formattedMessage })
    }

    companion object {
        /** 뒤 서비스 자리. commerce-service(토큰 없는 등급표 GET)와 chat-service(chat 토큰 라우트) 둘 다 여기로 보낸다. */
        private val server = MockWebServer().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun serviceAddresses(registry: DynamicPropertyRegistry) {
            val address = server.url("/").toString().trimEnd('/')
            registry.add("modu.services.commerce-service") { address }
            registry.add("modu.services.chat-service") { address }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.shutdown()
    }
}
