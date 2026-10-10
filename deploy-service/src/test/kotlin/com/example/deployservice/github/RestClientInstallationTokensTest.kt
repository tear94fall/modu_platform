package com.example.deployservice.github

import com.example.deployservice.api.UpstreamException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Instant

/**
 * 설치 토큰 교환 요청의 모양(POST, App JWT 를 Bearer 로)과 응답 해석, 그리고 설치 ID 를 안 적었을 때
 * `GET /app/installations` 로 **한 번** 찾아 기억하는지. 네트워크 없음.
 */
class RestClientInstallationTokensTest {

    private val builder: RestClient.Builder = RestClient.builder().baseUrl("https://api.github.com")
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()
    private val tokens = RestClientInstallationTokens(builder.build(), "98765432")

    @Test
    fun `a configured installation id is used as is`() {
        server.expect(requestTo("https://api.github.com/app/installations/98765432/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer jwt.header.sig"))
            .andRespond(
                withSuccess(
                    """{"token":"ghs_installation","expires_at":"2026-10-10T10:00:00Z","permissions":{"contents":"write"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val token = tokens.exchange("jwt.header.sig")

        assertEquals("ghs_installation", token.value)
        assertEquals(Instant.parse("2026-10-10T10:00:00Z"), token.expiresAt)
        server.verify()
    }

    @Test
    fun `a rejected exchange becomes an UpstreamException with a korean hint`() {
        server.expect(requestTo("https://api.github.com/app/installations/98765432/access_tokens"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("""{"message":"A JWT could not be decoded"}""").contentType(MediaType.APPLICATION_JSON))

        val e = assertThrows(UpstreamException::class.java) { tokens.exchange("jwt.header.sig") }

        assertEquals("github", e.system)
        assertEquals(401, e.httpStatus)
        assertTrue(e.message!!.contains("설치 토큰 발급 실패"), e.message)
        assertTrue(e.message!!.contains("deploy.github.app"), e.message)
        server.verify()
    }

    @Test
    fun `a blank installation id is discovered once and reused`() {
        val tokens = RestClientInstallationTokens(builder.build()) // installation-id 를 안 적은 경우
        server.expect(requestTo("https://api.github.com/app/installations"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer jwt.header.sig"))
            .andRespond(
                withSuccess("""[{"id":169966383,"account":{"login":"tear94fall"}}]""", MediaType.APPLICATION_JSON),
            )
        server.expect(ExpectedCount.times(2), requestTo("https://api.github.com/app/installations/169966383/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"token":"ghs_installation","expires_at":"2026-10-10T10:00:00Z"}""", MediaType.APPLICATION_JSON))

        assertEquals("ghs_installation", tokens.exchange("jwt.header.sig").value)
        assertEquals("ghs_installation", tokens.exchange("jwt.header.sig").value)

        server.verify() // /app/installations 는 한 번만 불렸다
    }

    @Test
    fun `an app without any installation fails with a korean message`() {
        val tokens = RestClientInstallationTokens(builder.build())
        server.expect(requestTo("https://api.github.com/app/installations"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

        val e = assertThrows(UpstreamException::class.java) { tokens.exchange("jwt.header.sig") }

        assertTrue(e.message!!.contains("설치되지 않았습니다"), e.message)
        assertTrue(e.message!!.contains("modu_infra"), e.message)
        server.verify()
    }

    @Test
    fun `a response without a token is an error too`() {
        server.expect(requestTo("https://api.github.com/app/installations/98765432/access_tokens"))
            .andRespond(withSuccess("""{"message":"ok"}""", MediaType.APPLICATION_JSON))

        val e = assertThrows(UpstreamException::class.java) { tokens.exchange("jwt.header.sig") }

        assertTrue(e.message!!.contains("token 이 없습니다"), e.message)
        server.verify()
    }
}
