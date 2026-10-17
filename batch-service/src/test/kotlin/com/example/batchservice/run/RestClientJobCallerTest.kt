package com.example.batchservice.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Duration

/** 서비스 호출 모양: 경로·헤더(내부 토큰, X-Request-Id)·오류 상태를 예외 없이 결과로. */
class RestClientJobCallerTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val caller = RestClientJobCaller({ builder.build() }, "secret")

    @Test
    fun `posts to the job run endpoint with the internal token and request id`() {
        server.expect(requestTo("http://commerce:8200/api-internal/v1/jobs/point-outbox-relay/run"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Internal-Token", "secret"))
            .andExpect(header("X-Request-Id", "job-abc"))
            .andRespond(withSuccess("""{"outcome":"ok"}""", MediaType.APPLICATION_JSON))

        val result = caller.call("http://commerce:8200", "point-outbox-relay", "job-abc", Duration.ofSeconds(30))

        assertEquals(200, result.status, result.error)
        assertEquals("""{"outcome":"ok"}""", result.body)
        assertFalse(result.timedOut)
        server.verify()
    }

    @Test
    fun `error statuses come back as results, not exceptions`() {
        server.expect(requestTo("http://commerce:8200/api-internal/v1/jobs/x/run"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body("""{"outcome":"failed","error":"boom"}"""))

        val result = caller.call("http://commerce:8200", "x", "job-1", Duration.ofSeconds(30))

        assertEquals(500, result.status)
        assertTrue(result.body!!.contains("boom"))
        assertNull(result.error)
    }
}
