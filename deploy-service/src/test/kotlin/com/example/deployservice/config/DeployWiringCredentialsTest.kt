package com.example.deployservice.config

import com.example.deployservice.gateway.GitHubRestGateway
import com.example.deployservice.github.GitHubAppCredentials
import com.example.deployservice.github.GitHubCredentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import java.security.KeyPairGenerator
import java.time.Clock
import java.util.Base64

/**
 * 자격 고르기(앱 → 레거시 토큰 → 익명)와 **요청마다** Authorization 을 묻는지. 네트워크 없음 —
 * 앱 자격은 만들어지기만 보고(토큰 교환은 [com.example.deployservice.github.RestClientInstallationTokensTest]) 호출하지 않는다.
 */
class DeployWiringCredentialsTest {

    private val wiring = DeployWiring()
    private val clock: Clock = Clock.systemUTC()

    private val pkcs8Pem: String = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair().private.encoded
        .let { "-----BEGIN PRIVATE KEY-----\n${Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(it)}\n-----END PRIVATE KEY-----\n" }

    private fun props(token: String = "", app: DeployProperties.GitHub.App = DeployProperties.GitHub.App()) =
        DeployProperties(github = DeployProperties.GitHub(owner = "tear94fall", token = token, app = app))

    @Test
    fun `the app wins over the legacy token`() {
        val credentials = wiring.gitHubCredentials(
            props(token = "ghp_legacy", app = DeployProperties.GitHub.App("Iv23li", "98765432", pkcs8Pem)),
            clock,
        )

        assertInstanceOf(GitHubAppCredentials::class.java, credentials)
    }

    @Test
    fun `only the legacy token gives a token header and nothing gives anonymous`() {
        assertEquals("token ghp_legacy", wiring.gitHubCredentials(props(token = "ghp_legacy"), clock).authorization())
        val anonymous = wiring.gitHubCredentials(props(), clock)
        assertSame(GitHubCredentials.ANONYMOUS, anonymous)
        assertNull(anonymous.authorization())
    }

    @Test
    fun `a pkcs1 key stops the startup`() {
        val pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nMIIEogIBAAKCAQEA\n-----END RSA PRIVATE KEY-----"

        val keyError = assertThrows(IllegalStateException::class.java) {
            wiring.gitHubCredentials(props(app = DeployProperties.GitHub.App("Iv23li", "98765432", pkcs1)), clock)
        }

        assertTrue(keyError.message!!.contains("openssl pkcs8 -topk8 -nocrypt"), keyError.message)
    }

    /**
     * 비밀키는 공개 저장소인 config-repo 가 아니라 k8s Secret(환경변수 DEPLOY_GITHUB_APP_PRIVATE_KEY)에서 온다.
     * 시크릿을 안 넣은 환경에서는 설정에 앱 번호만 있는데, 그걸로 기동이 실패하면 안 된다 — 앱 경로를 그냥 안 쓴다.
     */
    @Test
    fun `an app id without the private key falls back instead of failing`() {
        val credentials = wiring.gitHubCredentials(props(token = "ghp_legacy", app = DeployProperties.GitHub.App("Iv23li")), clock)

        assertEquals("token ghp_legacy", credentials.authorization())
        assertSame(GitHubCredentials.ANONYMOUS, wiring.gitHubCredentials(props(app = DeployProperties.GitHub.App("Iv23li")), clock))
    }

    /** 설치 ID 는 선택이다 — 없어도 기동한다(첫 호출 때 GET /app/installations 로 찾는다). */
    @Test
    fun `the app works without an installation id`() {
        val credentials = wiring.gitHubCredentials(props(app = DeployProperties.GitHub.App("Iv23li", "", pkcs8Pem)), clock)

        assertInstanceOf(GitHubAppCredentials::class.java, credentials)
    }

    @Test
    fun `the github client asks for the credential on every request`() {
        var current: String? = "token first"
        val builder = DeployWiring.gitHubClientBuilder("https://api.github.com", GitHubCredentials { current })
        val server = MockRestServiceServer.bindTo(builder).build()
        val gateway = GitHubRestGateway(builder.build(), "tear94fall")
        val commit = """{"commit":{"message":"m"}}"""
        server.expect(requestTo("https://api.github.com/repos/tear94fall/modu_chat/commits/aaaaaaa"))
            .andExpect(header("Authorization", "token first"))
            .andExpect(header("Accept", "application/vnd.github+json"))
            .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
            .andRespond(withSuccess(commit, MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.github.com/repos/tear94fall/modu_chat/commits/bbbbbbb"))
            .andExpect(header("Authorization", "Bearer refreshed"))
            .andRespond(withSuccess(commit, MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.github.com/repos/tear94fall/modu_chat/commits/ccccccc"))
            .andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess(commit, MediaType.APPLICATION_JSON))

        gateway.commitMessage("modu_chat", "aaaaaaa")
        current = "Bearer refreshed" // 설치 토큰이 갱신된 상황
        gateway.commitMessage("modu_chat", "bbbbbbb")
        current = null // 자격 없음 → 익명
        gateway.commitMessage("modu_chat", "ccccccc")

        server.verify()
    }
}
