package com.example.gatewayservice

import com.example.gatewayservice.config.DnsCacheConfig
import com.example.gatewayservice.config.DnsCacheConfig.Companion.withShortDnsCache
import io.netty.resolver.dns.DnsAddressResolverGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.autoconfigure.reactive.ClientHttpConnectorBuilderCustomizer
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder
import org.springframework.boot.http.client.reactive.ReactorClientHttpConnectorBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.gateway.config.HttpClientCustomizer
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * 라우팅용 HttpClient 와 WebClient 둘 다 짧은 DNS 캐시 리졸버를 쓰는지. compose 에서 컨테이너를 다시 띄우면 IP 가 바뀌는데,
 * 기본 리졸버는 레코드 TTL(도커 DNS 600s) 동안 옛 IP 를 쓰다가 Connection refused 를 낸다.
 */
@SpringBootTest(
    properties = [
        "modu.internal-api.token=test-internal-token",
        "modu.oauth.jwks-uri=http://localhost:9900/oauth2/jwks",
        "modu.oauth.issuer=http://localhost:8000/auth-service",
    ],
)
class DnsCacheConfigTest {

    @Autowired lateinit var customizers: List<HttpClientCustomizer>

    @Autowired lateinit var connectorCustomizers: List<ClientHttpConnectorBuilderCustomizer<ReactorClientHttpConnectorBuilder>>

    @Test
    fun routingHttpClient_getsShortDnsCache() {
        val client = customizers.fold(HttpClient.create()) { c, customizer -> customizer.customize(c) }
        assertTrue(client.configuration().resolver() is DnsAddressResolverGroup, "resolver = ${client.configuration().resolver()}")
    }

    @Test
    fun webClient_getsShortDnsCache() {
        // Boot 4: 커스터마이저는 빌더에 HttpClient 연산자를 쌓고 build() 때 순서대로 적용한다. 마지막 연산자로 결과를 잡아 본다.
        var captured: HttpClient? = null
        var builder = ClientHttpConnectorBuilder.reactor()
        connectorCustomizers.forEach { builder = it.customize(builder) }
        builder.withHttpClientCustomizer { c -> captured = c; c }.build()
        assertTrue(captured!!.configuration().resolver() is DnsAddressResolverGroup, "resolver = ${captured!!.configuration().resolver()}")
    }

    @Test
    fun ttlIsSeconds() {
        assertEquals(Duration.ofSeconds(5), DnsCacheConfig.DNS_CACHE_TTL)
        assertTrue(HttpClient.create().withShortDnsCache().configuration().resolver() is DnsAddressResolverGroup)
    }
}
