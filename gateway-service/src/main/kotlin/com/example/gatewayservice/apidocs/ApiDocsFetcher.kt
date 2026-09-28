package com.example.gatewayservice.apidocs

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cloud.client.loadbalancer.LoadBalanced
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration

/** 서비스 하나의 OpenAPI 문서(JSON)를 받아 온다. 실패·시간 초과는 에러로 끝나는 Mono. */
fun interface ApiDocsFetcher {
    /** [baseUri] 는 `lb://<서비스>`(Eureka) 또는 `http://<호스트>:<포트>`(직접). */
    fun fetch(name: String, baseUri: URI): Mono<JsonNode>
}

/**
 * `<baseUri>/v3/api-docs` 를 부른다. `lb://x` 는 로드밸런싱 WebClient 로 `http://x` 를 부르고(호스트 자리의 서비스 이름을
 * Eureka 인스턴스로 바꾼다), `http(s)://` 는 Eureka 에 없는 서비스(commerce-service)라 보통 WebClient 로 그대로 부른다.
 */
@Component
class LoadBalancedApiDocsFetcher(
    loadBalancedBuilder: WebClient.Builder,
    plainBuilder: WebClient.Builder,
    private val timeout: Duration,
) : ApiDocsFetcher {

    @Autowired
    constructor(
        @LoadBalanced loadBalancedBuilder: WebClient.Builder,
        plainBuilder: WebClient.Builder,
    ) : this(loadBalancedBuilder, plainBuilder, TIMEOUT)

    private val loadBalanced = loadBalancedBuilder.build()
    private val plain = plainBuilder.build()

    override fun fetch(name: String, baseUri: URI): Mono<JsonNode> {
        val (client, base) =
            if (baseUri.scheme.equals(ApiDocsService.LB_SCHEME, ignoreCase = true)) loadBalanced to "http://${baseUri.host}"
            else plain to baseUri.toString().trimEnd('/')
        return client.get().uri("$base/v3/api-docs")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .timeout(timeout)
    }

    companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
