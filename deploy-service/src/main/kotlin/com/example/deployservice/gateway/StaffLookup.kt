package com.example.deployservice.gateway

import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.util.concurrent.ConcurrentHashMap

/** 배포자 표시 이름. 게이트웨이가 주는 X-Auth-User-Id 는 구글 sub(숫자)라 사람이 못 알아본다 — member-service 에서 이름·이메일을 푼다. */
fun interface StaffLookup {
    /** 못 찾거나 member-service 가 죽어 있으면 null — 배포는 멈추지 않는다. */
    fun displayName(userId: String): String?
}

/** member-service `GET /api-internal/member/id/{userId}`(X-Internal-Token) 의 username → email 순. sub 별로 캐시한다(직원 수가 적다). */
class MemberServiceStaffLookup(private val members: RestClient) : StaffLookup {
    private val log = LoggerFactory.getLogger(MemberServiceStaffLookup::class.java)
    private val cache = ConcurrentHashMap<String, String>()

    override fun displayName(userId: String): String? =
        cache[userId] ?: try {
            val body = members.get().uri("/api-internal/member/id/{id}", userId).retrieve().body(Map::class.java)
            val name = listOf("username", "email").firstNotNullOfOrNull { k -> (body?.get(k) as? String)?.trim()?.takeIf { it.isNotEmpty() } }
            name?.also { cache[userId] = it }
        } catch (e: Exception) {
            log.warn("배포자 이름 조회 실패 userId={} message={}", userId, e.message)
            null
        }
}
