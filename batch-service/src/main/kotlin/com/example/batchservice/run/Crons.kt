package com.example.batchservice.run

import org.springframework.scheduling.support.CronExpression
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** 6자리 Spring cron(초 분 시 일 월 요일)과 타임존. 콘솔이 주기를 입력할 때 검증하고 다음 실행 시각을 미리 보여 준다. */
object Crons {

    /** 문제가 있으면 한국어 메시지, 없으면 null. */
    fun validate(cron: String, zone: String): String? {
        if (cron.isBlank()) return "cron 이 비어 있습니다."
        if (!CronExpression.isValidExpression(cron)) return "cron 식이 올바르지 않습니다(6자리: 초 분 시 일 월 요일): $cron"
        runCatching { ZoneId.of(zone) }.getOrElse { return "타임존이 올바르지 않습니다: $zone" }
        return null
    }

    /** [from] 뒤의 다음 실행 시각 [count] 개. 식이 틀리면 빈 목록. */
    fun next(cron: String, zone: String, count: Int, from: Instant): List<Instant> {
        if (validate(cron, zone) != null) return emptyList()
        val expression = CronExpression.parse(cron)
        var cursor = ZonedDateTime.ofInstant(from, ZoneId.of(zone))
        val out = mutableListOf<Instant>()
        repeat(count.coerceIn(0, 20)) {
            cursor = expression.next(cursor) ?: return out
            out += cursor.toInstant()
        }
        return out
    }
}
