package com.example.deployservice.deploy

import java.time.Instant

/** GHCR 패키지 버전 하나(한 이미지 다이제스트에 태그가 여럿 붙을 수 있다). */
data class ContainerVersion(val tags: List<String>, val createdAt: Instant)

/** 배포 가능한 태그 한 개. [sha] 는 태그의 커밋 7자리. */
data class DeployableTag(val tag: String, val sha: String, val createdAt: Instant)

/** 배포 가능한 태그는 CI 가 올리는 커밋 태그(`develop-<sha7>`, `master-<sha7>`)뿐이다. 움직이는 태그(develop, latest)는 뺀다. */
object Tags {
    val PATTERN = Regex("^(develop|master)-[0-9a-f]{7}$")
    const val DEFAULT_MAX = 30

    fun isDeployable(tag: String): Boolean = PATTERN.matches(tag)

    fun sha(tag: String): String = tag.substringAfter('-')

    /** 패턴에 맞는 태그만, 최신(createdAt) 순, 같은 태그는 한 번, 최대 [max] 개. */
    fun select(versions: List<ContainerVersion>, max: Int = DEFAULT_MAX): List<DeployableTag> =
        versions.flatMap { v -> v.tags.filter(::isDeployable).map { DeployableTag(it, sha(it), v.createdAt) } }
            .sortedByDescending { it.createdAt }
            .distinctBy { it.tag }
            .take(max)
}
