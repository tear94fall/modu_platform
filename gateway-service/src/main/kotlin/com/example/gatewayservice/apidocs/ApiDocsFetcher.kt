package com.example.gatewayservice.apidocs

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration

/** 서비스 하나의 OpenAPI 문서(JSON)를 받아 온다. 실패·시간 초과는 에러로 끝나는 Mono. */
fun interface ApiDocsFetcher {
    /** [baseUri] 는 `http://<호스트>:<포트>`(modu.services 의 주소). */
    fun fetch(name: String, baseUri: URI): Mono<JsonNode>
}

/** `<baseUri>/v3/api-docs` 를 보통 WebClient 로 그대로 부른다(호스트 이름은 compose·k8s 의 DNS 가 푼다). */
@Component
class WebClientApiDocsFetcher(
    builder: WebClient.Builder,
    private val timeout: Duration,
) : ApiDocsFetcher {

    @Autowired
    constructor(builder: WebClient.Builder) : this(builder, TIMEOUT)

    private val client = builder.build()

    override fun fetch(name: String, baseUri: URI): Mono<JsonNode> =
        client.get().uri("${baseUri.toString().trimEnd('/')}/v3/api-docs")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .timeout(timeout)

    companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
