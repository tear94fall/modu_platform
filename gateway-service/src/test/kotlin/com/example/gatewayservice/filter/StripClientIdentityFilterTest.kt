package com.example.gatewayservice.filter

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

class StripClientIdentityFilterTest {

    private val filter = StripClientIdentityFilter()
    private val chain: GatewayFilterChain = mock(GatewayFilterChain::class.java)

    @Test
    fun forgedUserIdHeader_isStrippedBeforeChain() {
        `when`(chain.filter(any())).thenReturn(Mono.empty())
        val ex = MockServerWebExchange.from(MockServerHttpRequest.get("/x").header("X-Auth-User-Id", "attacker").build())

        filter.filter(ex, chain).block()

        val captor = ArgumentCaptor.forClass(ServerWebExchange::class.java)
        verify(chain).filter(captor.capture())
        assertFalse(captor.value.request.headers.containsKey("X-Auth-User-Id"))
    }

    @Test
    fun requestWithoutHeader_passesThroughUnchanged() {
        `when`(chain.filter(any())).thenReturn(Mono.empty())
        val ex = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build())

        filter.filter(ex, chain).block()

        val captor = ArgumentCaptor.forClass(ServerWebExchange::class.java)
        verify(chain).filter(captor.capture())
        assertSame(ex, captor.value)
        assertFalse(captor.value.request.headers.containsKey("X-Auth-User-Id"))
    }
}
