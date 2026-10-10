package com.example.deployservice.gateway

import com.example.deployservice.api.UpstreamException
import com.example.deployservice.deploy.ContainerVersion
import com.example.deployservice.deploy.Tags
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * GHCR 을 **레지스트리 API(`/v2/...`)로** 읽는다. GitHub Packages REST API(`/users/{owner}/packages/container/...`)는
 * 공개 패키지라도 인증을 요구하고 **fine-grained 토큰·GitHub App 으로는 쓸 수 없다** — 그래서 레지스트리로 옮겼다.
 *
 * 인증은 GitHub 자격과 **무관하다**: 저장소가 public 이라 익명 pull 토큰
 * (`GET /token?scope=repository:<owner>/<pkg>:pull&service=ghcr.io`)만으로 태그와 매니페스트를 읽을 수 있다.
 * 그래서 [client] 는 `https://ghcr.io` 전용이고 GitHub Authorization 초기화를 붙이지 않는다.
 *
 * 태그에 찍힌 날짜는 레지스트리 목록에 없어 이미지 설정 블롭의 `created` 를 읽는다(매니페스트 → (인덱스면) 자식 매니페스트
 * → 설정 블롭, 태그마다 3번). 그래서 (패키지, 태그)별로 캐시한다 — 여기 쓰는 커밋 태그는 다시 찍히지 않는다.
 * 날짜를 못 읽으면 그 태그만 날짜 없이(= [Instant.EPOCH], 기존 Packages API 구현과 같은 값) 돌려주고 전체를 실패시키지 않는다.
 *
 * 경로는 문자열로 직접 만든다 — URI 템플릿 변수로 주면 패키지 이름의 `/` 와 다이제스트의 `:` 가 인코딩돼 레지스트리가 404 를 준다.
 * 값은 설정(`deploy.services[].image`)에서 오는 신뢰된 이름이다.
 * 응답은 문자열로 받아 직접 파싱한다 — 매니페스트는 `application/vnd.oci.image.index.v1+json`, 설정 블롭은
 * (스토리지로 리다이렉트된 뒤) `application/octet-stream` 으로 와서 Content-Type 만 보는 변환기에 맡길 수 없다.
 */
class GhcrRegistryGateway(
    private val client: RestClient,
    private val owner: String,
    private val clock: Clock = Clock.systemUTC(),
) : ContainerRegistryGateway {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()

    /** 패키지별 익명 pull 토큰. */
    private val pullTokens = ConcurrentHashMap<String, CachedToken>()

    /** (패키지, 태그) → 이미지 생성 시각. 태그는 움직이지 않으므로 한 번 읽으면 끝이다. */
    private val createdAt = ConcurrentHashMap<String, Instant>()

    private data class CachedToken(val value: String, val expiresAt: Instant)

    companion object {
        const val SERVICE = "ghcr.io"

        /** pull 토큰은 보통 5분을 산다 — 그보다 짧게 잡고 다시 받는다. */
        val TOKEN_TTL: Duration = Duration.ofMinutes(4)

        /**
         * 한 번의 조회에서 날짜를 새로 읽을 태그 수 상한(태그당 3 요청). 레지스트리는 태그를 사전순으로 주므로
         * "최신 N개"를 고를 수 없다 — 캐시에 있는 건 공짜로 다 쓰고, 새로 읽는 것만 이만큼으로 막는다.
         * 남은 태그는 날짜 없이(EPOCH) 나가고 [Tags.select] 의 최신순 정렬에서 뒤로 밀린다.
         */
        const val MAX_DATE_LOOKUPS = 60

        const val MANIFEST_ACCEPT =
            "application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, " +
                "application/vnd.docker.distribution.manifest.v2+json"
    }

    override fun tagNames(packageName: String): List<String> = call("태그 목록 조회") {
        val node = client.get()
            .uri { b -> b.path("/v2/${repository(packageName)}/tags/list").build() }
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${pullToken(packageName)}")
            .retrieve().body(String::class.java).let(::json) ?: return@call emptyList()
        node.path("tags").map { it.asText("") }.filter { it.isNotBlank() }
    }

    override fun containerVersions(packageName: String): List<ContainerVersion> {
        val tags = tagNames(packageName).filter(Tags::isDeployable)
        val dates = dates(packageName, tags)
        return tags.map { ContainerVersion(tags = listOf(it), createdAt = dates[it] ?: Instant.EPOCH) }
    }

    /** 캐시에 있는 태그는 모두, 나머지는 [MAX_DATE_LOOKUPS] 개까지 읽는다. */
    private fun dates(packageName: String, tags: List<String>): Map<String, Instant> {
        val found = LinkedHashMap<String, Instant>()
        tags.forEach { tag -> createdAt[key(packageName, tag)]?.let { found[tag] = it } }
        var budget = MAX_DATE_LOOKUPS
        for (tag in tags) {
            if (found.containsKey(tag)) continue
            if (budget-- <= 0) break
            imageCreatedAt(packageName, tag)?.let { found[tag] = it; createdAt[key(packageName, tag)] = it }
        }
        return found
    }

    /** 매니페스트 → (인덱스면) 자식 매니페스트 → 설정 블롭의 `created`. 하나라도 어긋나면 null(그 태그만 날짜 없이 나간다). */
    private fun imageCreatedAt(packageName: String, tag: String): Instant? = try {
        val token = pullToken(packageName)
        val digest = configDigest(packageName, manifest(packageName, tag, token), token)
        digest?.let { blob(packageName, it, token) }
            ?.path("created")?.asText("")
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
    } catch (e: Exception) {
        log.debug("ghcr created-at unavailable for {}:{} — {}", packageName, tag, e.message)
        null
    }

    private fun manifest(packageName: String, reference: String, token: String): JsonNode? = client.get()
        .uri { b -> b.path("/v2/${repository(packageName)}/manifests/$reference").build() }
        .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        .header(HttpHeaders.ACCEPT, MANIFEST_ACCEPT)
        .retrieve().body(String::class.java).let(::json)

    /**
     * 설정 블롭의 다이제스트. 매니페스트 인덱스(멀티 플랫폼)면 `platform.os` 가 `unknown` 이 아닌 자식을 골라 한 번 더 읽는다 —
     * `unknown/unknown` 은 attestation(provenance·SBOM)이라 이미지가 아니다.
     */
    private fun configDigest(packageName: String, manifest: JsonNode?, token: String): String? {
        val node = manifest ?: return null
        val children = node.path("manifests")
        if (children.isArray && !children.isEmpty) {
            val child = children.firstOrNull { it.path("platform").path("os").asText("") != "unknown" } ?: return null
            val digest = child.path("digest").asText("").takeIf { it.isNotBlank() } ?: return null
            return manifest(packageName, digest, token)?.path("config")?.path("digest")?.asText("")?.takeIf { it.isNotBlank() }
        }
        return node.path("config").path("digest").asText("").takeIf { it.isNotBlank() }
    }

    /** 설정 블롭(스토리지로 리다이렉트될 수 있다 — 요청 팩토리가 따라간다). */
    private fun blob(packageName: String, digest: String, token: String): JsonNode? = client.get()
        .uri { b -> b.path("/v2/${repository(packageName)}/blobs/$digest").build() }
        .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        .retrieve().body(String::class.java).let(::json)

    /** 패키지별 익명 pull 토큰(짧게 캐시). 저장소가 public 이라 자격 없이 받을 수 있다. */
    private fun pullToken(packageName: String): String {
        val now = clock.instant()
        pullTokens[packageName]?.takeIf { it.expiresAt.isAfter(now) }?.let { return it.value }
        val node = client.get()
            .uri { b ->
                b.path("/token")
                    .queryParam("scope", "repository:${repository(packageName)}:pull")
                    .queryParam("service", SERVICE)
                    .build()
            }
            .retrieve().body(String::class.java).let(::json)
        val token = node?.path("token")?.asText("").orEmpty()
        if (token.isBlank()) throw UpstreamException("ghcr", "GHCR pull 토큰 응답에 token 이 없습니다: $packageName")
        pullTokens[packageName] = CachedToken(token, now.plus(TOKEN_TTL))
        return token
    }

    private fun json(body: String?): JsonNode? = body?.takeIf { it.isNotBlank() }?.let { runCatching { mapper.readTree(it) }.getOrNull() }

    private fun repository(packageName: String): String = "$owner/${packageName.trim('/')}"

    private fun key(packageName: String, tag: String): String = "$packageName:$tag"

    private inline fun <T> call(what: String, block: () -> T): T = try {
        block()
    } catch (e: RestClientResponseException) {
        throw UpstreamException("ghcr", "GHCR $what 실패 (${e.statusCode.value()}): ${e.responseBodyAsString.take(200)}", e, e.statusCode.value())
    } catch (e: UpstreamException) {
        throw e
    } catch (e: Exception) {
        throw UpstreamException("ghcr", "GHCR $what 실패: ${e.message}", e)
    }
}
