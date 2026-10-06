package com.example.deployservice.logging

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
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse

/** 요청 id 결정·MDC·응답 헤더·http.access 로그를 필터 단위로 본다(컨텍스트 없이). */
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

    /** 체인 안(컨트롤러 자리)에서 보이는 MDC 를 기억하고 상태를 넣는다. */
    private class Capturing(private val status: Int = 200) : MockFilterChain() {
        var requestId: String? = null
        var userId: String? = null

        override fun doFilter(request: ServletRequest, response: ServletResponse) {
            requestId = MDC.get("requestId")
            userId = MDC.get("userId")
            (response as MockHttpServletResponse).status = status
        }
    }

    private fun request(path: String = "/api-system/deploy/services", requestId: String? = null, userId: String? = null) =
        MockHttpServletRequest("GET", path).apply {
            if (requestId != null) addHeader("X-Request-Id", requestId)
            if (userId != null) addHeader("X-Auth-User-Id", userId)
        }

    @Test
    fun missingHeader_generatesId_setsMdcAndResponseHeader() {
        val res = MockHttpServletResponse()
        val chain = Capturing()

        filter.doFilter(request(), res, chain)

        val id = chain.requestId!!
        assertEquals(16, id.length)
        assertTrue(RequestContextFilter.isValidRequestId(id), id)
        assertEquals(id, res.getHeader("X-Request-Id"))
        assertNull(chain.userId)
    }

    @Test
    fun validHeader_isEchoed() {
        val res = MockHttpServletResponse()
        val chain = Capturing()

        filter.doFilter(request(requestId = "gw-abc_123"), res, chain)

        assertEquals("gw-abc_123", chain.requestId)
        assertEquals("gw-abc_123", res.getHeader("X-Request-Id"))
    }

    @Test
    fun invalidHeader_isReplaced() {
        for (bad in listOf("has space", "x".repeat(65), "", "a;b", "한글")) {
            val res = MockHttpServletResponse()
            val chain = Capturing()

            filter.doFilter(request(requestId = bad), res, chain)

            assertNotEquals(bad, chain.requestId)
            assertTrue(RequestContextFilter.isValidRequestId(chain.requestId), chain.requestId)
            assertEquals(chain.requestId, res.getHeader("X-Request-Id"))
        }
    }

    @Test
    fun userIdHeader_goesToMdc_andMdcIsClearedAfter() {
        val chain = Capturing(404)

        filter.doFilter(request(requestId = "r1", userId = "7"), MockHttpServletResponse(), chain)

        assertEquals("7", chain.userId)
        assertNull(MDC.get("requestId"))
        assertNull(MDC.get("userId"))

        val event = appender.list.single()
        assertEquals("GET /api-system/deploy/services 404", event.formattedMessage.substringBeforeLast(" "))
        assertEquals("r1", event.mdcPropertyMap["requestId"])
        assertEquals("7", event.mdcPropertyMap["userId"])
        assertTrue(event.argumentArray.map { it.toString() }.contains("event=http.access"))
    }

    @Test
    fun mdcIsClearedEvenWhenTheChainThrows() {
        val throwing = object : MockFilterChain() {
            override fun doFilter(request: ServletRequest, response: ServletResponse) = throw IllegalStateException("boom")
        }

        runCatching { filter.doFilter(request(requestId = "r2", userId = "7"), MockHttpServletResponse(), throwing) }

        assertNull(MDC.get("requestId"))
        assertNull(MDC.get("userId"))
        assertEquals("r2", appender.list.single().mdcPropertyMap["requestId"])
    }

    @Test
    fun actuator_isNotLogged_butStillGetsHeader() {
        val res = MockHttpServletResponse()

        filter.doFilter(request(path = "/actuator/health/readiness"), res, Capturing())

        assertTrue(appender.list.isEmpty())
        assertTrue(RequestContextFilter.isValidRequestId(res.getHeader("X-Request-Id")))
    }
}
