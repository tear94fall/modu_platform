package com.example.deployservice.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Instant

/** 새 파드의 ERROR 로그를 OpenSearch 에서 — 질의 모양(파드 keyword terms · level ERROR · since)과 응답 파싱, 실패 시 count null + error. */
class OpenSearchLogsGatewayTest {

    private val builder: RestClient.Builder = RestClient.builder().baseUrl("http://opensearch:9200")
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()
    private val gateway = OpenSearchLogsGateway(builder.build(), "modu-app-logs*")
    private val since = Instant.parse("2026-11-15T10:00:00Z")

    @Test
    fun `queries error lines of the given pods since a time and parses them newest first`() {
        server.expect(requestTo("http://opensearch:9200/modu-app-logs*/_search")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"size":20,"track_total_hits":true,"sort":[{"@timestamp":"desc"}],"query":{"bool":{"filter":[
                {"terms":{"attributes.pod.keyword":["point-service-new-1"]}},{"term":{"attributes.level.keyword":"ERROR"}},{"range":{"@timestamp":{"gte":"2026-11-15T10:00:00Z"}}}]}}}""", false))
            .andRespond(withSuccess("""{"hits":{"total":{"value":3,"relation":"eq"},"hits":[
                {"_source":{"@timestamp":"2026-11-15T10:05:00Z","attributes":{"pod":"point-service-new-1"},"body":"NullPointerException at X"}},
                {"_source":{"@timestamp":"2026-11-15T10:04:00Z","attributes":{"pod":"point-service-new-1"},"body":"second"}}]}}""", MediaType.APPLICATION_JSON))

        val logs = gateway.errors(listOf("point-service-new-1"), since, 20)

        assertEquals(3L, logs.count)
        assertNull(logs.error)
        assertEquals(2, logs.lines.size)
        assertEquals(Instant.parse("2026-11-15T10:05:00Z"), logs.lines[0].time)
        assertEquals("point-service-new-1", logs.lines[0].pod)
        assertEquals("NullPointerException at X", logs.lines[0].message)
        server.verify()
    }

    @Test
    fun `an OpenSearch failure yields count null and an error instead of throwing`() {
        server.expect(requestTo("http://opensearch:9200/modu-app-logs*/_search")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        val logs = gateway.errors(listOf("p"), since, 20)

        assertNull(logs.count)
        assertTrue(logs.lines.isEmpty())
        assertNotNull(logs.error)
    }

    @Test
    fun `no pods means no query and zero errors`() {
        val logs = gateway.errors(emptyList(), since, 20)
        assertEquals(0L, logs.count)
        server.verify()
    }
}
