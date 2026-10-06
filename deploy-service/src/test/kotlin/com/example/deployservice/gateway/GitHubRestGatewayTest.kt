package com.example.deployservice.gateway

import com.example.deployservice.api.UpstreamException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.Base64

/** 실제 GitHub 응답 모양으로 URI(특히 패키지 이름의 %2F)·헤더·본문을 본다. 네트워크 없음. */
class GitHubRestGatewayTest {

    private val builder: RestClient.Builder = RestClient.builder()
        .baseUrl("https://api.github.com")
        .defaultHeader("Authorization", "Bearer gh-token")
        .defaultHeader("Accept", "application/vnd.github+json")
        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()
    private val gateway = GitHubRestGateway(builder.build(), "tear94fall")

    @Test
    fun `container versions encode the package slash and keep tags and created_at`() {
        server.expect(requestTo("https://api.github.com/users/tear94fall/packages/container/modu-chat%2Fpoint-service/versions?per_page=50"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer gh-token"))
            .andExpect(header("Accept", "application/vnd.github+json"))
            .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
            .andRespond(
                withSuccess(
                    """[
                      {"id":1,"created_at":"2026-10-06T12:45:00Z","metadata":{"package_type":"container","container":{"tags":["develop-5708871","develop"]}}},
                      {"id":2,"created_at":"2026-10-05T00:00:00Z","metadata":{"package_type":"container","container":{"tags":[]}}}
                    ]""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val versions = gateway.containerVersions("modu-chat/point-service")

        assertEquals(listOf("develop-5708871", "develop"), versions[0].tags)
        assertEquals(Instant.parse("2026-10-06T12:45:00Z"), versions[0].createdAt)
        assertTrue(versions[1].tags.isEmpty())
        server.verify()
    }

    @Test
    fun `get file decodes base64 content and put file sends sha branch and message`() {
        val yaml = "images:\n  - name: ghcr.io/x/y\n    newTag: develop\n"
        server.expect(requestTo("https://api.github.com/repos/tear94fall/modu_infra/contents/k8s/overlays/dev/kustomization.yaml?ref=main"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""{"sha":"blob1","encoding":"base64","content":"${Base64.getMimeEncoder().encodeToString(yaml.toByteArray())}"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.github.com/repos/tear94fall/modu_infra/contents/k8s/overlays/dev/kustomization.yaml"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.sha").value("blob1"))
            .andExpect(jsonPath("$.branch").value("main"))
            .andExpect(jsonPath("$.message").value("deploy: y → develop-1234567 (by me)"))
            .andExpect(jsonPath("$.content").value(Base64.getEncoder().encodeToString(yaml.replace("develop\n", "develop-1234567\n").toByteArray())))
            .andRespond(withSuccess("""{"content":{"sha":"blob2"},"commit":{"sha":"c0ffee","html_url":"https://github.com/tear94fall/modu_infra/commit/c0ffee"}}""", MediaType.APPLICATION_JSON))

        val file = gateway.getFile("modu_infra", "main", "k8s/overlays/dev/kustomization.yaml")
        assertEquals("blob1", file.sha)
        assertEquals(yaml, file.content)
        val commit = gateway.putFile("modu_infra", "main", "k8s/overlays/dev/kustomization.yaml", yaml.replace("develop\n", "develop-1234567\n"), "blob1", "deploy: y → develop-1234567 (by me)")
        assertEquals(RepoCommit("c0ffee", "https://github.com/tear94fall/modu_infra/commit/c0ffee"), commit)
        server.verify()
    }

    @Test
    fun `commit message is the first line and errors become UpstreamException`() {
        server.expect(requestTo("https://api.github.com/repos/tear94fall/modu_chat/commits/5708871"))
            .andRespond(withSuccess("""{"sha":"5708871abc","commit":{"message":"Merge pull request #423 from x\n\nbody"}}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.github.com/repos/tear94fall/modu_infra/branches/main"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).body("""{"message":"Not Found"}""").contentType(MediaType.APPLICATION_JSON))

        assertEquals("Merge pull request #423 from x", gateway.commitMessage("modu_chat", "5708871"))
        val e = assertThrows(UpstreamException::class.java) { gateway.branchHead("modu_infra", "main") }
        assertEquals("github", e.system)
        assertTrue(e.message!!.contains("404"), e.message)
        server.verify()
    }
}
