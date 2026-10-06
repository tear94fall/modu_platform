package com.example.deployservice.gateway

import com.example.deployservice.api.UpstreamException
import com.example.deployservice.deploy.ContainerVersion
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/**
 * GitHub REST API(Contents·Commits·Packages). [client] 는 baseUrl(api-url)과 인증·버전 헤더를 갖고 있다([DeployWiring]).
 * 경로는 URI 템플릿으로 준다 — RestClient 의 기본 인코딩(TEMPLATE_AND_VALUES)이 변수 값의 `/` 를 `%2F` 로 적어 주므로
 * GHCR 패키지 이름(`modu-chat/point-service`)이 경로 한 조각으로 들어간다. 파일 경로는 `/` 를 살려야 해서 조각마다 변수로 준다.
 */
class GitHubRestGateway(private val client: RestClient, private val owner: String) : GitHubGateway {

    companion object {
        const val VERSIONS_PER_PAGE = 50
    }

    override fun getFile(repo: String, branch: String, path: String): RepoFile = call("파일 읽기") {
        val node = client.get().uri(contentsTemplate(path) + "?ref={ref}", *contentsVars(repo, path, branch))
            .retrieve().body(JsonNode::class.java)!!
        val content = node.path("content").asText("")
        RepoFile(sha = node.path("sha").asText(), content = String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8))
    }

    override fun putFile(repo: String, branch: String, path: String, content: String, sha: String, message: String): RepoCommit =
        call("파일 커밋") {
            val body = mapOf(
                "message" to message,
                "content" to Base64.getEncoder().encodeToString(content.toByteArray(StandardCharsets.UTF_8)),
                "sha" to sha,
                "branch" to branch,
            )
            val node = client.put().uri(contentsTemplate(path), *contentsVars(repo, path))
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode::class.java)!!
            val commit = node.path("commit")
            RepoCommit(commit.path("sha").asText(), commit.path("html_url").asText())
        }

    override fun branchHead(repo: String, branch: String): RepoCommit = call("브랜치 조회") {
        val node = client.get().uri("/repos/{owner}/{repo}/branches/{branch}", owner, repo, branch)
            .retrieve().body(JsonNode::class.java)!!
        val commit = node.path("commit")
        RepoCommit(commit.path("sha").asText(), commit.path("html_url").asText())
    }

    override fun containerVersions(packageName: String): List<ContainerVersion> = call("GHCR 패키지 조회") {
        val node = client.get()
            .uri("/users/{owner}/packages/container/{pkg}/versions?per_page={n}", owner, packageName, VERSIONS_PER_PAGE)
            .retrieve().body(JsonNode::class.java) ?: return@call emptyList()
        node.map { v ->
            ContainerVersion(
                tags = v.path("metadata").path("container").path("tags").map { it.asText() },
                createdAt = runCatching { Instant.parse(v.path("created_at").asText()) }.getOrDefault(Instant.EPOCH),
            )
        }
    }

    override fun commitMessage(repo: String, sha: String): String? = call("커밋 조회") {
        val node = client.get().uri("/repos/{owner}/{repo}/commits/{sha}", owner, repo, sha).retrieve().body(JsonNode::class.java)
        node?.path("commit")?.path("message")?.asText()?.lineSequence()?.firstOrNull()?.trim()
    }

    /** `/repos/{owner}/{repo}/contents/{p0}/{p1}/...` — 경로 조각마다 변수 하나(조각 안의 특수문자만 인코딩된다). */
    private fun contentsTemplate(path: String): String =
        "/repos/{owner}/{repo}/contents/" + path.split('/').indices.joinToString("/") { "{p$it}" }

    private fun contentsVars(repo: String, path: String, vararg tail: String): Array<Any> =
        arrayOf(owner, repo, *path.split('/').toTypedArray(), *tail)

    private inline fun <T> call(what: String, block: () -> T): T = try {
        block()
    } catch (e: RestClientResponseException) {
        throw UpstreamException("github", "GitHub $what 실패 (${e.statusCode.value()}): ${e.responseBodyAsString.take(200)}", e)
    } catch (e: UpstreamException) {
        throw e
    } catch (e: Exception) {
        throw UpstreamException("github", "GitHub $what 실패: ${e.message}", e)
    }
}
