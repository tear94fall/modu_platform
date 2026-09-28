package com.example.gatewayservice.apidocs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.gateway.config.GatewayProperties
import org.springframework.cloud.gateway.route.RouteDefinition
import org.springframework.cloud.gateway.support.NameUtils
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.net.URI

data class ApiDocsServiceList(val services: List<ApiDocsServiceItem>)

/** [routed] 가 false 면 게이트웨이로 이 서비스의 HTTP 경로에 닿을 길이 없다(콘솔이 Try it out 을 끈다). */
data class ApiDocsServiceItem(val name: String, val title: String, val routed: Boolean)

/** 문서를 받을 곳. [baseUri] 는 `lb://<서비스>` 또는 `http://<호스트>:<포트>`. */
data class ApiDocsTarget(val name: String, val baseUri: URI, val routed: Boolean)

/** 문서가 없는 이름(목록에 없는 서비스). 컨트롤러가 404 로 바꾼다. */
class UnknownServiceException(name: String) : RuntimeException("알 수 없는 서비스입니다: $name")

/** 받아 오지 못했거나 OpenAPI JSON 객체가 아니다. 컨트롤러가 502 로 바꾼다. */
class ApiDocsFetchException(name: String, cause: Throwable? = null) : RuntimeException("$name 문서를 불러오지 못했습니다", cause)

/**
 * 백엔드 서비스들의 API 문서. 목록은 라우트 설정(GatewayProperties)의 `lb://`·`lb:ws://` 대상에
 * `modu.api-docs.extra-services` 를 더한 것이고, 문서는 그 서비스의 `/v3/api-docs` 를 그대로 가져와
 * `servers` 만 게이트웨이 경유 주소(`/<서비스>`)로 바꾼다.
 */
@Service
@EnableConfigurationProperties(ApiDocsProperties::class)
class ApiDocsService(
    private val gatewayProperties: GatewayProperties,
    private val properties: ApiDocsProperties,
    private val fetcher: ApiDocsFetcher,
) {

    /**
     * 이름순 목록. 라우트에서 뽑은 서비스가 먼저 자리를 잡고(`lb:ws://x` 도 http 문서는 `lb://x`), extra-services 는 없는 이름만 더한다.
     * 게이트웨이 자신과 discovery/config 는 뺀다.
     */
    fun targets(): List<ApiDocsTarget> {
        val routes = gatewayProperties.routes
        val baseUris = linkedMapOf<String, URI>()
        routes.mapNotNull { lbServiceOf(it.uri) }.forEach { name -> baseUris.putIfAbsent(name, URI.create("lb://$name")) }
        properties.extraServices.forEach { (name, uri) -> baseUris.putIfAbsent(name.lowercase(), uri) }
        return baseUris
            .filterKeys { it !in EXCLUDED }
            .map { (name, uri) -> ApiDocsTarget(name, uri, routed = isRouted(routes, name)) }
            .sortedBy { it.name }
    }

    fun services() = ApiDocsServiceList(targets().map { ApiDocsServiceItem(name = it.name, title = it.name, routed = it.routed) })

    fun docs(name: String): Mono<ObjectNode> {
        val target = targets().firstOrNull { it.name == name } ?: return Mono.error(UnknownServiceException(name))
        return fetcher.fetch(name, target.baseUri)
            .onErrorMap { ApiDocsFetchException(name, it) }
            .flatMap { body ->
                if (body is ObjectNode) Mono.just(withGatewayServer(body, name)) else Mono.error(ApiDocsFetchException(name))
            }
            .switchIfEmpty(Mono.error { ApiDocsFetchException(name) })
    }

    companion object {
        const val LB_SCHEME = "lb"
        const val SERVER_DESCRIPTION = "게이트웨이 경유"
        val EXCLUDED = setOf("gateway-service", "discovery-service", "config-service")

        /** `lb://X` → x, `lb:ws://X`·`lb:wss://X` → x. 그 밖(http:// 등)은 null. */
        fun lbServiceOf(uri: URI?): String? {
            if (uri == null || !uri.scheme.equals(LB_SCHEME, ignoreCase = true)) return null
            // lb:ws://X 는 java.net.URI 에선 불투명 URI(scheme=lb, ssp=ws://X)라 host 가 없다.
            val host = uri.host ?: runCatching { URI(uri.schemeSpecificPart).host }.getOrNull()
            return host?.lowercase()
        }

        /**
         * HTTP 라우트의 Path 술어가 `/<서비스>/...` 를 받으면 게이트웨이 경유로 부를 수 있다.
         * WebSocket 라우트(`lb:ws://`, `ws://`, `wss://`)는 HTTP 를 넘기지 않으므로 세지 않는다(ws-service 는 false).
         */
        fun isRouted(routes: List<RouteDefinition>, name: String): Boolean {
            val prefix = "/$name/"
            return routes.filterNot { isWebSocket(it.uri) }.any { route ->
                route.predicates.filter { it.name == "Path" }.any { p ->
                    p.args.filterKeys { it.startsWith(NameUtils.GENERATED_NAME_PREFIX) || it == "patterns" }
                        .values.any { it.trim().startsWith(prefix) }
                }
            }
        }

        fun isWebSocket(uri: URI?): Boolean {
            if (uri == null) return false
            val scheme = if (uri.scheme.equals(LB_SCHEME, ignoreCase = true) && uri.host == null) {
                uri.schemeSpecificPart.substringBefore("://")
            } else {
                uri.scheme
            }
            return scheme.equals("ws", ignoreCase = true) || scheme.equals("wss", ignoreCase = true)
        }

        /** 서비스가 알려 준 servers(컨테이너 내부 주소)를 게이트웨이 경유 주소 하나로 바꾼다. */
        fun withGatewayServer(doc: ObjectNode, name: String): ObjectNode {
            val server = JsonNodeFactory.instance.objectNode()
                .put("url", "/$name")
                .put("description", SERVER_DESCRIPTION)
            doc.set<JsonNode>("servers", JsonNodeFactory.instance.arrayNode().add(server))
            return doc
        }
    }
}
