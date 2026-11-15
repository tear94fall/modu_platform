package com.example.deployservice.gateway

import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.time.Instant

data class LogLine(val time: Instant, val pod: String, val message: String)

/** 새 파드의 ERROR 로그 요약. 조회 실패면 [count] 가 null 이고 [error] 에 사유 — 화면은 "로그 조회 실패" 를 보여 주고 배포는 막지 않는다. */
data class ErrorLogs(val count: Long?, val lines: List<LogLine>, val error: String? = null)

/** 로그 저장소(OpenSearch). 카나리 대기 중 콘솔이 "새 파드 에러 로그" 패널로 2~5초마다 부른다 — 액티비티가 아니라 조회 시점 계산. */
interface LogsGateway {
    fun errors(pods: List<String>, since: Instant, limit: Int = 20): ErrorLogs
}

/**
 * OpenSearch `POST {index}/_search`. 필드는 2026-11-15 dev 매핑으로 확인: `attributes.pod.keyword`(파드 이름), `attributes.level.keyword`(ERROR), `body`(메시지), `@timestamp`.
 * 응답은 최신순, `track_total_hits` 로 전체 건수.
 */
class OpenSearchLogsGateway(private val client: RestClient, private val index: String) : LogsGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun errors(pods: List<String>, since: Instant, limit: Int): ErrorLogs {
        if (pods.isEmpty()) return ErrorLogs(0, emptyList())
        val podsJson = pods.joinToString(",") { "\"$it\"" }
        val body = """{"size":$limit,"track_total_hits":true,"sort":[{"@timestamp":"desc"}],"query":{"bool":{"filter":[""" +
            """{"terms":{"attributes.pod.keyword":[$podsJson]}},{"term":{"attributes.level.keyword":"ERROR"}},{"range":{"@timestamp":{"gte":"$since"}}}]}}}"""
        return try {
            val root = client.post().uri("/$index/_search").header("Content-Type", "application/json").body(body).retrieve().body(JsonNode::class.java)
                ?: return ErrorLogs(null, emptyList(), "빈 응답")
            val hits = root.path("hits")
            val total = hits.path("total").path("value").asLong(0)
            val lines = hits.path("hits").iterator().asSequence().map { h ->
                val src = h.path("_source")
                LogLine(
                    time = runCatching { Instant.parse(src.path("@timestamp").asString()) }.getOrDefault(Instant.EPOCH),
                    pod = src.path("attributes").path("pod").asString(),
                    message = src.path("body").asString(),
                )
            }.toList()
            ErrorLogs(total, lines)
        } catch (e: Exception) {
            log.warn("opensearch error-log query failed: {}", e.message)
            ErrorLogs(null, emptyList(), "로그 조회 실패: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
