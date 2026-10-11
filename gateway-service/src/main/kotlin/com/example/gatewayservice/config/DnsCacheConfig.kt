package com.example.gatewayservice.config

import org.springframework.boot.http.client.autoconfigure.reactive.ClientHttpConnectorBuilderCustomizer
import org.springframework.boot.http.client.reactive.ReactorClientHttpConnectorBuilder
import org.springframework.cloud.gateway.config.HttpClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * 라우팅 대상은 호스트 이름(`modu.services.*`)으로 부르는데, Reactor Netty 의 DNS 리졸버는 응답을 레코드 TTL 만큼 캐시한다.
 * compose 에선 컨테이너를 다시 띄우면 IP 가 바뀌어서, 캐시가 남아 있는 동안 옛 IP 로 붙다가 Connection refused 가 난다
 * (2026-10-03 dev 에서 chat-service 재배포 뒤 재현). 캐시를 짧게(5초) 잡아 재배포 직후에도 새 IP 를 바로 본다.
 * k8s 에선 Service 의 ClusterIP 가 고정이라 문제가 없지만, 설정은 그대로 두어도 해롭지 않다.
 */
@Configuration
class DnsCacheConfig {

    /** 게이트웨이 라우팅용 HttpClient(스프링 클라우드 게이트웨이가 만든 것)에 짧은 DNS 캐시를 건다. */
    @Bean
    fun shortDnsCacheHttpClientCustomizer(): HttpClientCustomizer =
        HttpClientCustomizer { client -> client.withShortDnsCache() }

    /**
     * API 문서 조회용 WebClient(WebClient.Builder 자동 설정)도 같은 리졸버 설정을 쓴다.
     * Boot 4 는 ReactorNettyHttpClientMapper 를 없애고, 자동 설정이 만드는 커넥터 빌더에 커스터마이저를 거는 방식으로 바꿨다.
     */
    @Bean
    fun shortDnsCacheConnectorCustomizer(): ClientHttpConnectorBuilderCustomizer<ReactorClientHttpConnectorBuilder> =
        ClientHttpConnectorBuilderCustomizer { builder -> builder.withHttpClientCustomizer { client -> client.withShortDnsCache() } }

    companion object {
        val DNS_CACHE_TTL: Duration = Duration.ofSeconds(5)

        fun HttpClient.withShortDnsCache(): HttpClient =
            resolver { spec -> spec.cacheMaxTimeToLive(DNS_CACHE_TTL) }
    }
}
