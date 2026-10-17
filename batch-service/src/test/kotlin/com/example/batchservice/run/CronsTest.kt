package com.example.batchservice.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/** 6자리 Spring cron 검증과 다음 실행 시각 미리보기(콘솔이 주기를 입력할 때 보여 준다). */
class CronsTest {
    private val from = Instant.parse("2026-10-17T03:00:00Z") // 서울 12:00

    @Test
    fun `valid cron and zone have no error`() {
        assertNull(Crons.validate("0 0 4 * * *", "Asia/Seoul"))
        assertNull(Crons.validate("0 */2 * * * *", "UTC"))
    }

    @Test
    fun `bad cron or zone yields a message`() {
        assertNotNull(Crons.validate("* * *", "Asia/Seoul"))
        assertNotNull(Crons.validate("0 0 4 * * *", "Mars/Olympus"))
        assertNotNull(Crons.validate("", "Asia/Seoul"))
    }

    @Test
    fun `next run times follow the zone`() {
        val next = Crons.next("0 0 4 * * *", "Asia/Seoul", 2, from)
        assertEquals(listOf(Instant.parse("2026-10-17T19:00:00Z"), Instant.parse("2026-10-18T19:00:00Z")), next)
        assertEquals(Instant.parse("2026-10-17T03:02:00Z"), Crons.next("0 */2 * * * *", "Asia/Seoul", 1, from).single())
    }
}
