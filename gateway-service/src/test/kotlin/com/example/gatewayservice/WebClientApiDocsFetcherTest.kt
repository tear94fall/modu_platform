package com.example.gatewayservice

import com.example.gatewayservice.apidocs.WebClientApiDocsFetcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 실제 WebClient 로 /v3/api-docs 를 부른다(서비스 주소 자리에 MockWebServer). 네트워크 밖으로 나가지 않는다. */
class WebClientApiDocsFetcherTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    private fun fetcher(timeout: Duration = Duration.ofSeconds(2)) = WebClientApiDocsFetcher(WebClient.builder(), timeout)

    /** modu.services 값처럼 `http://host:port`(끝 슬래시 없음). */
    private val base get() = URI.create(server.url("/").toString().trimEnd('/'))

    /** 에러로 끝난 Mono 의 에러(값으로 끝나면 null). */
    private fun errorOf(mono: Mono<*>): Throwable? =
        mono.map<Throwable?> { null }.onErrorResume { Mono.just(it) }.block(Duration.ofSeconds(5))

    @Test
    fun fetchesV3ApiDocsUnderTheServiceAddress() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"openapi":"3.0.1","servers":[]}"""))

        val doc = fetcher().fetch("member-service", base).block(Duration.ofSeconds(5))!!
        assertEquals("3.0.1", doc["openapi"].asText())

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v3/api-docs", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test
    fun trailingSlashInAddressDoesNotDoubleTheSlash() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"openapi":"3.0.1"}"""))

        fetcher().fetch("commerce-service", URI.create("$base/")).block(Duration.ofSeconds(5))!!
        assertEquals("/v3/api-docs", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun errorStatusFails() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertInstanceOf(WebClientResponseException::class.java, errorOf(fetcher().fetch("chat-service", base)))
    }

    @Test
    fun slowResponseTimesOut() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("{}").setHeadersDelay(2, TimeUnit.SECONDS),
        )
        assertInstanceOf(TimeoutException::class.java, errorOf(fetcher(Duration.ofMillis(200)).fetch("commerce-service", base)))
    }

    @Test
    fun defaultTimeoutIsFiveSeconds() {
        assertEquals(Duration.ofSeconds(5), WebClientApiDocsFetcher.TIMEOUT)
    }
}
