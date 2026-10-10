package com.example.deployservice.github

import com.example.deployservice.api.UpstreamException
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Duration
import java.time.Instant

/**
 * 설치 토큰을 실제로 받아 오는 쪽. [client] 는 GitHub API baseUrl 만 가진 클라이언트다 —
 * 여기 요청은 App JWT 로 인증하므로 [GitHubCredentials] 를 붙이지 않는다(붙이면 자기를 다시 부른다).
 *
 * [configuredInstallationId] 는 **없어도 된다** — 비어 있으면 `GET /app/installations` 로 찾아 기억한다
 * (운영자는 App ID 와 비밀키만 내려주면 된다). 설치가 하나도 없으면 한국어 메시지로 실패한다.
 *
 * 실패는 [UpstreamException]("github") 으로 올린다 — 응답 본문을 메시지에 조금 싣지만 성공 응답(토큰이 든)은 싣지 않는다.
 */
class RestClientInstallationTokens(
    private val client: RestClient,
    private val configuredInstallationId: String = "",
) : InstallationTokenExchange {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 설정값, 없으면 찾아낸 값. 교환은 [GitHubAppCredentials] 의 잠금 안에서 불리므로 한 번만 찾는다. */
    @Volatile
    private var installationId: String? = configuredInstallationId.takeIf { it.isNotBlank() }

    companion object {
        /** `expires_at` 을 못 읽었을 때 쓰는 보수적인 수명(GitHub 는 1시간을 준다). */
        val FALLBACK_LIFETIME: Duration = Duration.ofMinutes(30)
    }

    override fun exchange(appJwt: String): InstallationToken = try {
        val id = installationId ?: discover(appJwt).also { installationId = it }
        val node = client.post()
            .uri("/app/installations/{installationId}/access_tokens", id)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $appJwt")
            .retrieve().body(JsonNode::class.java)
            ?: throw UpstreamException("github", "GitHub 앱 설치 토큰 응답이 비어 있습니다.")
        val token = node.path("token").asText("")
        if (token.isBlank()) throw UpstreamException("github", "GitHub 앱 설치 토큰 응답에 token 이 없습니다.")
        InstallationToken(token, expiresAt(node.path("expires_at").asText("")))
    } catch (e: RestClientResponseException) {
        throw UpstreamException(
            "github",
            "GitHub 앱 설치 토큰 발급 실패 (${e.statusCode.value()}): ${e.responseBodyAsString.take(200)} " +
                "— deploy.github.app 의 id·private-key(와 적었다면 installation-id)와 App 설치 상태를 확인하세요.",
            e,
            e.statusCode.value(),
        )
    } catch (e: UpstreamException) {
        throw e
    } catch (e: Exception) {
        throw UpstreamException("github", "GitHub 앱 설치 토큰 발급 실패: ${e.message}", e)
    }

    /**
     * `GET /app/installations` — 앱이 설치된 곳. modu_infra 한 곳만 설치하므로 보통 하나다.
     * 여러 개면 첫 번째를 쓰고 경고한다(그럴 땐 `deploy.github.app.installation-id` 를 적어 주는 게 맞다).
     */
    private fun discover(appJwt: String): String {
        val node = client.get()
            .uri("/app/installations")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $appJwt")
            .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .retrieve().body(JsonNode::class.java)
        val ids = node?.mapNotNull { it.path("id").asText("").takeIf { id -> id.isNotBlank() } }.orEmpty()
        if (ids.isEmpty()) {
            throw UpstreamException(
                "github",
                "GitHub App 이 아직 어디에도 설치되지 않았습니다 — modu_infra 에 설치하거나 deploy.github.app.installation-id 를 내려주세요.",
            )
        }
        if (ids.size > 1) log.warn("github app 설치가 {}개입니다 — 첫 번째({})를 씁니다. installation-id 를 지정하세요.", ids.size, ids.first())
        log.info("github app installation {} 을 찾았습니다.", ids.first())
        return ids.first()
    }

    private fun expiresAt(raw: String): Instant =
        runCatching { Instant.parse(raw) }.getOrElse { Instant.now().plus(FALLBACK_LIFETIME) }
}
