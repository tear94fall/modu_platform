package com.example.gatewayservice

import com.example.gatewayservice.apidocs.LoadBalancedApiDocsFetcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.junit.jupiter.api.Assertions.assertInstanceOf
import reactor.core.publisher.Mono
import org.springframework.web.reactive.function.client.ClientRequest
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 실제 WebClient 로 /v3/api-docs 를 부른다(로드밸런서 대신 MockWebServer 주소). 네트워크 밖으로 나가지 않는다. */
class LoadBalancedApiDocsFetcherTest {

    private lateinit var server: MockWebServer
    /** 로드밸런서 대신: http://<서비스>/... 를 MockWebServer 로 돌리고, 부른 서비스 이름을 적어 둔다. */
    private val balancedHosts = mutableListOf<String>()

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    private fun fakeLoadBalancer() = WebClient.builder().filter { request, next ->
        balancedHosts += request.url().host
        val target = URI.create(server.url(request.url().rawPath).toString())
        next.exchange(ClientRequest.from(request).url(target).build())
    }

    private fun fetcher(timeout: Duration = Duration.ofSeconds(2)) =
        LoadBalancedApiDocsFetcher(fakeLoadBalancer(), WebClient.builder(), timeout)

    private val direct get() = URI.create(server.url("/").toString().trimEnd('/'))

    /** 에러로 끝난 Mono 의 에러(값으로 끝나면 null). */
    private fun errorOf(mono: Mono<*>): Throwable? =
        mono.map<Throwable?> { null }.onErrorResume { Mono.just(it) }.block(Duration.ofSeconds(5))

    @Test
    fun lbUriGoesThroughTheLoadBalancedClient() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"openapi":"3.0.1","servers":[]}"""))

        val doc = fetcher().fetch("member-service", URI.create("lb://member-service")).block(Duration.ofSeconds(5))!!
        assertEquals("3.0.1", doc["openapi"].asText())

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v3/api-docs", request.path)
        assertEquals(listOf("member-service"), balancedHosts)
    }

    @Test
    fun httpUriGoesDirectlyWithoutTheLoadBalancer() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"openapi":"3.0.1"}"""))

        val doc = fetcher().fetch("commerce-service", direct).block(Duration.ofSeconds(5))!!
        assertEquals("3.0.1", doc["openapi"].asText())
        assertEquals("/v3/api-docs", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        assertEquals(emptyList<String>(), balancedHosts)
    }

    @Test
    fun errorStatusFails() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertInstanceOf(WebClientResponseException::class.java, errorOf(fetcher().fetch("chat-service", URI.create("lb://chat-service"))))
    }

    @Test
    fun slowResponseTimesOut() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("{}").setHeadersDelay(2, TimeUnit.SECONDS),
        )
        assertInstanceOf(TimeoutException::class.java, errorOf(fetcher(Duration.ofMillis(200)).fetch("commerce-service", direct)))
    }

    @Test
    fun defaultTimeoutIsFiveSeconds() {
        assertEquals(Duration.ofSeconds(5), LoadBalancedApiDocsFetcher.TIMEOUT)
    }
}
