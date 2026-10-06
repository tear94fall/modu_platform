package com.example.deployservice.deploy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class TagsAndProgressTest {

    private fun at(s: String) = Instant.parse(s)

    @Test
    fun `only commit tags pass the pattern`() {
        assertTrue(Tags.isDeployable("develop-5708871"))
        assertTrue(Tags.isDeployable("master-abcdef0"))
        assertFalse(Tags.isDeployable("develop"))
        assertFalse(Tags.isDeployable("latest"))
        assertFalse(Tags.isDeployable("develop-570887")) // 6자리
        assertFalse(Tags.isDeployable("develop-57088712")) // 8자리
        assertFalse(Tags.isDeployable("develop-ABCDEF0")) // 대문자
        assertFalse(Tags.isDeployable("pr-12"))
        assertFalse(Tags.isDeployable("feature-5708871"))
    }

    @Test
    fun `selects matching tags newest first, deduplicated and capped`() {
        val versions = listOf(
            ContainerVersion(listOf("develop-aaaaaaa", "develop"), at("2026-10-01T00:00:00Z")),
            ContainerVersion(listOf("pr-12"), at("2026-10-05T00:00:00Z")),
            ContainerVersion(listOf("develop-ccccccc"), at("2026-10-06T00:00:00Z")),
            ContainerVersion(listOf("master-bbbbbbb", "latest"), at("2026-10-03T00:00:00Z")),
            ContainerVersion(listOf("develop-aaaaaaa"), at("2026-09-01T00:00:00Z")), // 같은 태그가 다른 버전에도(재태깅)
            ContainerVersion(emptyList(), at("2026-10-07T00:00:00Z")), // 태그 없는 다이제스트
        )

        val selected = Tags.select(versions)

        assertEquals(listOf("develop-ccccccc", "master-bbbbbbb", "develop-aaaaaaa"), selected.map { it.tag })
        assertEquals(listOf("ccccccc", "bbbbbbb", "aaaaaaa"), selected.map { it.sha })
        assertEquals(at("2026-10-01T00:00:00Z"), selected[2].createdAt)

        val many = (0 until 40).map { i -> ContainerVersion(listOf("develop-%07x".format(i)), at("2026-01-01T00:00:00Z").plusSeconds(i.toLong())) }
        assertEquals(30, Tags.select(many).size)
        assertEquals("develop-%07x".format(39), Tags.select(many).first().tag)
    }

    @Test
    fun `rollout percent is 50 plus half the ready ratio`() {
        assertEquals(50, Progress.rollout(0, 2))
        assertEquals(75, Progress.rollout(1, 2))
        assertEquals(100, Progress.rollout(2, 2))
        assertEquals(66, Progress.rollout(1, 3))
        assertEquals(100, Progress.rollout(0, 0)) // 복제본 0 이면 기다릴 게 없다
        assertEquals(100, Progress.rollout(5, 2)) // 넘치면 100
        assertEquals(50, Progress.rollout(-1, 2))
        assertEquals(5, Progress.COMMIT_START)
        assertEquals(20, Progress.COMMIT_DONE)
        assertEquals(50, Progress.SYNC_DONE)
    }
}
