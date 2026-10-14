package com.example.deployservice.deploy

import com.example.deployservice.api.ApiException
import com.example.deployservice.api.UpstreamException
import com.example.deployservice.deploy.DeployProps.IMAGE
import com.example.deployservice.deploy.DeployProps.props
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 태그 목록 조립: GHCR 레지스트리에서 받은 태그에 커밋 메시지를 붙인다. 커밋 조회가 403(rate limit)이면
 * **메시지 없이 태그가 나가야** 하고(배포가 막히면 안 된다) 남은 태그에 대고 다시 부르지 않는다.
 */
class DeployServiceTagsTest {

    private val kustomization = """
        images:
          - name: $IMAGE
            newTag: develop-35db83f
    """.trimIndent()

    private val github = FakeGitHub(kustomization)
    private val registry = FakeRegistry()
    private val kubernetes = FakeKubernetes()
    private val store = InMemoryDeploymentStore()
    private val clock = MutableClock()
    private val service = DefaultDeployService(props, github, registry, kubernetes, store, DeploymentRunner(store, DeploymentExecutor(props, github, FakeArgo(), kubernetes, store, clock)), clock)

    private fun version(tag: String, at: String) = ContainerVersion(listOf(tag), Instant.parse(at))

    @Test
    fun `tags come from the registry newest first with the current one marked`() {
        registry.versions = listOf(
            version("develop-35db83f", "2026-10-08T00:00:00Z"),
            version("master-76c3b5c", "2026-10-09T00:00:00Z"),
        )
        github.commitMessages["modu_chat/35db83f"] = "Feat: 포인트"

        val tags = service.tags("point-service").tags

        assertEquals(listOf("master-76c3b5c", "develop-35db83f"), tags.map { it.tag })
        assertEquals(listOf(false, true), tags.map { it.current }, "kustomization 의 태그가 current")
        assertEquals("https://github.com/tear94fall/modu_chat/commit/35db83f", tags[1].commitUrl)
        assertEquals("Feat: 포인트", tags[1].commitMessage)
        assertNull(tags[0].commitMessage, "메시지를 모르는 태그는 null")
    }

    @Test
    fun `a rate limited commit lookup leaves the tags without a message and is not retried`() {
        registry.versions = (0 until 5).map { version("develop-%07x".format(it), "2026-10-0${it + 1}T00:00:00Z") }
        github.commitFailure = UpstreamException("github", "GitHub 커밋 조회 실패 (403): API rate limit exceeded", null, 403)

        val tags = service.tags("point-service").tags

        assertEquals(5, tags.size, "태그 목록은 그대로 나간다")
        assertTrue(tags.all { it.commitMessage == null }, tags.toString())
        assertEquals(1, github.commitCalls, "한 번 실패하면 남은 태그에는 다시 묻지 않는다")
    }

    @Test
    fun `a registry failure is a bad gateway with a korean message`() {
        registry.fail = UpstreamException("ghcr", "GHCR 태그 목록 조회 실패 (429): too many requests", null, 429)

        val e = assertThrows(ApiException::class.java) { service.tags("point-service") }

        assertEquals("github_error", e.error)
        assertTrue(e.message!!.contains("GHCR 태그 조회 실패"), e.message)
    }

    @Test
    fun `deploy checks the tag with the cheap tag name call`() {
        registry.versions = listOf(version("develop-35db83f", "2026-10-08T00:00:00Z"))

        val e = assertThrows(ApiException::class.java) { service.deploy("point-service", "develop-0000000", "me") }

        assertEquals("unknown_tag", e.error)
        assertEquals(1, registry.tagNameCalls)
        assertEquals(0, registry.versionCalls, "배포 확인은 날짜를 읽지 않는다")
    }
}
