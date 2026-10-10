package com.example.deployservice.github

import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * GitHub App 자신을 증명하는 JWT(RS256). 설치 토큰을 받을 때만 쓰고 저장하지 않는다.
 *
 * 라이브러리를 더 넣지 않고 `java.security.Signature` 로 서명한다 — 헤더·클레임은 고정 모양이라 JSON 직렬화도 필요 없다.
 * [SKEW] 만큼 과거의 `iat` 를 적는 건 GitHub 쪽 시계가 조금 느릴 때 "iat 가 미래" 로 거절당하지 않게 하려는 것이고,
 * `exp` 는 GitHub 의 상한(10분)보다 짧은 [LIFETIME] 이다.
 */
object GitHubAppJwt {

    /** `{"alg":"RS256","typ":"JWT"}` */
    const val HEADER_JSON = """{"alg":"RS256","typ":"JWT"}"""

    val SKEW: Duration = Duration.ofSeconds(60)
    val LIFETIME: Duration = Duration.ofMinutes(9)

    /** [appId] (App ID 또는 Client ID)를 `iss` 로 적고 [key] 로 서명한 JWT. */
    fun create(appId: String, key: RSAPrivateKey, now: Instant): String {
        val claims = """{"iat":${now.minus(SKEW).epochSecond},"exp":${now.plus(LIFETIME).epochSecond},"iss":"$appId"}"""
        val signingInput = "${encode(HEADER_JSON.toByteArray(Charsets.UTF_8))}.${encode(claims.toByteArray(Charsets.UTF_8))}"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(key)
            update(signingInput.toByteArray(Charsets.US_ASCII))
        }.sign()
        return "$signingInput.${encode(signature)}"
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
