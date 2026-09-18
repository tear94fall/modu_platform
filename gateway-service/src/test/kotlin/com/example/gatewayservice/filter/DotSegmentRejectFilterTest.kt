package com.example.gatewayservice.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

class DotSegmentRejectFilterTest {

    private val filter = DotSegmentRejectFilter()
    private val chain: GatewayFilterChain = mock(GatewayFilterChain::class.java)

    @Test
    fun dotDotSegment_isDetected() {
        assertTrue(DotSegmentRejectFilter.hasDotSegment("/a/../b"))
    }

    @Test
    fun encodedDotDotSegment_isDetected() {
        assertTrue(DotSegmentRejectFilter.hasDotSegment("/a/%2e%2e/b"))
    }

    @Test
    fun singleDotSegment_isDetected() {
        assertTrue(DotSegmentRejectFilter.hasDotSegment("/a/./b"))
    }

    @Test
    fun dotsWithinSegment_isNotDetected() {
        assertFalse(DotSegmentRejectFilter.hasDotSegment("/a/b..c"))
    }

    @Test
    fun normalAdminPath_isNotDetected() {
        assertFalse(DotSegmentRejectFilter.hasDotSegment("/api-admin/member"))
    }

    @Test
    fun filter_rejectsDotSegmentPathWithoutCallingChain() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api-public/../api-internal").build())

        filter.filter(exchange, chain).block()

        assertEquals(HttpStatus.BAD_REQUEST, exchange.response.statusCode)
        verify(chain, never()).filter(exchange)
    }
}
