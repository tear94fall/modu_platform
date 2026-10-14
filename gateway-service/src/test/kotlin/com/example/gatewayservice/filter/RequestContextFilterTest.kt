package com.example.gatewayservice.filter

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/** 요청 id 결정·전달·응답 헤더와 http.access 로그를 필터 단위로 본다. */
class RequestContextFilterTest {

    private val filter = RequestContextFilter()
    private val appender = ListAppender<ILoggingEvent>()
    private val accessLog = LoggerFactory.getLogger("http.access") as Logger

    @BeforeEach
    fun attach() {
        appender.start()
        accessLog.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        accessLog.detachAppender(appender)
    }

    /** 체인은 뒤 서비스 자리: 받은 exchange 를 기억하고 상태를 넣고 응답을 커밋한다. */
    private class Downstream(private val status: HttpStatus = HttpStatus.OK) : WebFilterChain {
        var received: ServerWebExchange? = null
        var mdcRequestId: String? = null

        override fun filter(exchange: ServerWebExchange): Mono<Void> {
            received = exchange
            mdcRequestId = MDC.get("requestId")
            exchange.response.statusCode = status
            return exchange.response.setComplete()
        }
    }

    private fun exchange(requestId: String? = null, path: String = "/x"): MockServerWebExchange {
        val builder = MockServerHttpRequest.get(path)
        if (requestId != null) builder.header("X-Request-Id", requestId)
        return MockServerWebExchange.from(builder.build())
    }

    @Test
    fun missingHeader_generatesIdForDownstreamAndResponse() {
        val ex = exchange()
        val chain = Downstream()

        filter.filter(ex, chain).block()

        val forwarded = chain.received!!.request.headers.getFirst("X-Request-Id")!!
        assertTrue(RequestContextFilter.isValidRequestId(forwarded), forwarded)
        assertEquals(16, forwarded.length)
        assertEquals(listOf(forwarded), ex.response.headers["X-Request-Id"])
    }

    @Test
    fun validHeader_isEchoed() {
        val ex = exchange("abc-DEF_123")
        val chain = Downstream()

        filter.filter(ex, chain).block()

        assertEquals(listOf("abc-DEF_123"), chain.received!!.request.headers["X-Request-Id"])
        assertEquals("abc-DEF_123", ex.response.headers.getFirst("X-Request-Id"))
    }

    @Test
    fun invalidHeader_isReplaced() {
        for (bad in listOf("has space", "a\nb", "x".repeat(65), "", "한글", "a;b")) {
            val ex = exchange(bad)
            val chain = Downstream()

            filter.filter(ex, chain).block()

            val forwarded = chain.received!!.request.headers.getFirst("X-Request-Id")!!
            assertNotEquals(bad, forwarded)
            assertTrue(RequestContextFilter.isValidRequestId(forwarded), forwarded)
            assertEquals(forwarded, ex.response.headers.getFirst("X-Request-Id"))
        }
    }

    @Test
    fun accessLog_carriesMethodPathStatusAndRequestIdInMdc_andMdcIsClearedAfter() {
        val ex = exchange("req-1", "/chat-service/api-public/chat/1/rooms?page=2")
        ex.attributes[RequestContextFilter.USER_ID_ATTR] = "7"

        filter.filter(ex, Downstream(HttpStatus.CREATED)).block()

        assertEquals(1, appender.list.size)
        val event = appender.list.single()
        // "GET /path 201 12ms" — 시간은 빼고, 쿼리 문자열은 경로에 없어야 한다
        assertEquals("GET /chat-service/api-public/chat/1/rooms 201", event.formattedMessage.substringBeforeLast(" "))
        assertEquals("req-1", event.mdcPropertyMap["requestId"])
        assertEquals("7", event.mdcPropertyMap["userId"])
        assertTrue(event.argumentArray.map { it.toString() }.contains("event=http.access"))
        assertNull(MDC.get("requestId"))
        assertNull(MDC.get("userId"))
    }

    @Test
    fun accessLog_withoutUser_hasNoUserIdKey() {
        filter.filter(exchange("req-2"), Downstream()).block()
        val event = appender.list.single()
        assertEquals("req-2", event.mdcPropertyMap["requestId"])
        assertEquals(false, event.mdcPropertyMap.containsKey("userId"))
    }

    @Test
    fun actuator_isNotLogged_butStillGetsHeader() {
        val ex = exchange(null, "/actuator/health/liveness")
        val chain = Downstream()

        filter.filter(ex, chain).block()

        assertTrue(appender.list.isEmpty())
        assertTrue(RequestContextFilter.isValidRequestId(chain.received!!.request.headers.getFirst("X-Request-Id")))
    }

    @Test
    fun chainError_isLoggedWithTheExceptionStatus() {
        val ex = exchange("req-3")
        val failing = WebFilterChain { Mono.error(ResponseStatusException(HttpStatus.NOT_FOUND)) }

        val result = runCatching { filter.filter(ex, failing).block() }

        assertTrue(result.isFailure) // 예외는 삼키지 않는다(예외 처리기가 응답을 쓴다)
        val event = appender.list.single()
        assertEquals("GET /x 404", event.formattedMessage.substringBeforeLast(" "))
        assertEquals("req-3", event.mdcPropertyMap["requestId"])
    }

    @Test
    fun downstreamThread_doesNotSeeMdc() {
        // 리액터 컨텍스트 전파는 하지 않는다. MDC 는 로그 한 줄을 찍는 순간에만 있다.
        val chain = Downstream()
        filter.filter(exchange("req-4"), chain).block()
        assertNull(chain.mdcRequestId)
    }
}
