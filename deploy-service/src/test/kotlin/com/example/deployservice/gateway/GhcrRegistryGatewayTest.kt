package com.example.deployservice.gateway

import com.example.deployservice.api.UpstreamException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Instant

/**
 * GHCR 레지스트리 API 로 태그를 읽는다(Packages REST API 를 쓰지 않는다). 익명 pull 토큰 → 태그 목록 →
 * (매니페스트·설정 블롭으로) 생성 시각. 네트워크 없음.
 */
class GhcrRegistryGatewayTest {

    private val builder: RestClient.Builder = RestClient.builder().baseUrl("https://ghcr.io")
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build()
    private val gateway = GhcrRegistryGateway(builder.build(), "tear94fall")

    private val pkg = "modu-chat/point-service"
    private val repo = "tear94fall/modu-chat/point-service"

    private fun expectToken(times: Int = 1) {
        server.expect(ExpectedCount.times(times), requestTo("https://ghcr.io/token?scope=repository:$repo:pull&service=ghcr.io"))
            .andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess("""{"token":"anon-pull-token"}""", MediaType.APPLICATION_JSON))
    }

    private fun expectTags(vararg tags: String) {
        server.expect(requestTo("https://ghcr.io/v2/$repo/tags/list"))
            .andExpect(header("Authorization", "Bearer anon-pull-token"))
            .andRespond(withSuccess("""{"name":"$repo","tags":[${tags.joinToString(",") { "\"$it\"" }}]}""", MediaType.APPLICATION_JSON))
    }

    /** 매니페스트 인덱스: 이미지 자식 하나 + attestation(unknown/unknown) 하나. */
    private fun expectIndex(tag: String, childDigest: String) {
        server.expect(requestTo("https://ghcr.io/v2/$repo/manifests/$tag"))
            .andExpect(header("Accept", GhcrRegistryGateway.MANIFEST_ACCEPT))
            .andRespond(
                withSuccess(
                    """{"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[
                         {"digest":"sha256:attest","platform":{"architecture":"unknown","os":"unknown"}},
                         {"digest":"$childDigest","platform":{"architecture":"arm64","os":"linux"}}
                       ]}""",
                    MediaType.valueOf("application/vnd.oci.image.index.v1+json"),
                ),
            )
    }

    private fun expectManifest(reference: String, configDigest: String) {
        server.expect(requestTo("https://ghcr.io/v2/$repo/manifests/$reference"))
            .andRespond(
                withSuccess(
                    """{"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"digest":"$configDigest"}}""",
                    MediaType.valueOf("application/vnd.oci.image.manifest.v1+json"),
                ),
            )
    }

    /** 설정 블롭은 스토리지에서 octet-stream 으로 온다 — Content-Type 에 의존하지 않는다. */
    private fun expectConfigBlob(digest: String, created: String) {
        server.expect(requestTo("https://ghcr.io/v2/$repo/blobs/$digest"))
            .andRespond(
                withSuccess(
                    """{"created":"$created","config":{"Labels":{"org.opencontainers.image.revision":"35db83f0000000000000000000000000000000ab"}}}""",
                    MediaType.APPLICATION_OCTET_STREAM,
                ),
            )
    }

    @Test
    fun `only commit tags come back and the date comes from the image config blob`() {
        expectToken()
        expectTags("develop", "latest", "pr-12", "develop-35db83f", "master-76c3b5c")
        expectIndex("develop-35db83f", "sha256:child1")
        expectManifest("sha256:child1", "sha256:config1")
        expectConfigBlob("sha256:config1", "2026-10-06T12:45:00Z")
        expectIndex("master-76c3b5c", "sha256:child2")
        expectManifest("sha256:child2", "sha256:config2")
        expectConfigBlob("sha256:config2", "2026-10-02T01:00:00Z")

        val versions = gateway.containerVersions(pkg)

        assertEquals(listOf(listOf("develop-35db83f"), listOf("master-76c3b5c")), versions.map { it.tags })
        assertEquals(Instant.parse("2026-10-06T12:45:00Z"), versions[0].createdAt)
        assertEquals(Instant.parse("2026-10-02T01:00:00Z"), versions[1].createdAt)
        server.verify()
    }

    @Test
    fun `a single manifest without an index is handled and dates are cached per tag`() {
        expectToken()
        expectTags("develop-35db83f")
        expectManifest("develop-35db83f", "sha256:config1")
        expectConfigBlob("sha256:config1", "2026-10-06T12:45:00Z")
        expectTags("develop-35db83f") // 두 번째 조회: 태그 목록만 다시 읽고 날짜는 캐시에서

        val first = gateway.containerVersions(pkg)
        val second = gateway.containerVersions(pkg)

        assertEquals(Instant.parse("2026-10-06T12:45:00Z"), first.single().createdAt)
        assertEquals(first, second)
        server.verify() // 매니페스트·블롭이 한 번만 불렸다는 뜻
    }

    @Test
    fun `a blob that cannot be read degrades to no date instead of failing`() {
        expectToken()
        expectTags("develop-35db83f")
        expectManifest("develop-35db83f", "sha256:config1")
        server.expect(requestTo("https://ghcr.io/v2/$repo/blobs/sha256:config1"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).body("blob unknown"))

        val versions = gateway.containerVersions(pkg)

        assertEquals(listOf("develop-35db83f"), versions.single().tags)
        assertEquals(Instant.EPOCH, versions.single().createdAt, "날짜만 비고 태그는 나간다")
        server.verify()
    }

    @Test
    fun `tag names skip the date lookups entirely`() {
        expectToken()
        expectTags("develop", "develop-35db83f")

        assertEquals(listOf("develop", "develop-35db83f"), gateway.tagNames(pkg))
        server.verify()
    }

    @Test
    fun `a registry failure becomes an UpstreamException for ghcr`() {
        server.expect(requestTo("https://ghcr.io/token?scope=repository:$repo:pull&service=ghcr.io"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("""{"errors":[{"code":"TOOMANYREQUESTS"}]}"""))

        val e = assertThrows(UpstreamException::class.java) { gateway.tagNames(pkg) }

        assertEquals("ghcr", e.system)
        assertEquals(429, e.httpStatus)
        assertTrue(e.message!!.contains("태그 목록 조회 실패"), e.message)
        server.verify()
    }
}
