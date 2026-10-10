package com.example.deployservice.gateway

import com.example.deployservice.api.UpstreamException
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * GitHub REST API(Contents·Commits). [client] 는 baseUrl(api-url)과 Accept·버전 헤더를 갖고 있고,
 * `Authorization` 은 **요청마다** [com.example.deployservice.github.GitHubCredentials] 에서 받는다([DeployWiring]) —
 * GitHub App 설치 토큰은 한 시간이면 끝나므로 만들 때 한 번 박아 둘 수 없다. 여기 모든 호출(modu_infra 의 파일 읽기·커밋,
 * 소스 저장소의 커밋 메시지)이 같은 자격을 쓴다 — 설치 토큰은 설치되지 않은 공개 저장소도 읽는다. 자격이 아예 없으면
 * 익명으로 나가고 공개 저장소 읽기는 그래도 되지만(시간당 60번) 쓰기는 실패한다.
 *
 * 경로는 URI 템플릿으로 준다. 파일 경로는 `/` 를 살려야 해서 조각마다 변수로 준다
 * (RestClient 의 기본 인코딩 TEMPLATE_AND_VALUES 가 변수 값의 `/` 를 `%2F` 로 바꾸므로).
 *
 * GHCR 태그는 여기 없다 — [GhcrRegistryGateway] 가 레지스트리 API 로 읽는다.
 */
class GitHubRestGateway(private val client: RestClient, private val owner: String) : GitHubGateway {

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
        throw UpstreamException("github", "GitHub $what 실패 (${e.statusCode.value()}): ${e.responseBodyAsString.take(200)}", e, e.statusCode.value())
    } catch (e: UpstreamException) {
        throw e
    } catch (e: Exception) {
        throw UpstreamException("github", "GitHub $what 실패: ${e.message}", e)
    }
}
