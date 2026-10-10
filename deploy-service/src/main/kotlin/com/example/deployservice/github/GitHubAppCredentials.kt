package com.example.deployservice.github

import org.slf4j.LoggerFactory
import java.security.interfaces.RSAPrivateKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 설치 토큰 한 개: 값과 만료 시각(GitHub 는 한 시간을 준다). 값은 로그에 남기지 않는다. */
data class InstallationToken(val value: String, val expiresAt: Instant) {
    override fun toString(): String = "InstallationToken(value=***, expiresAt=$expiresAt)"
}

/**
 * `POST {apiUrl}/app/installations/{installationId}/access_tokens` 한 번. 구현은 [RestClientInstallationTokens],
 * 테스트는 호출 수를 세는 가짜를 끼운다.
 */
fun interface InstallationTokenExchange {
    fun exchange(appJwt: String): InstallationToken
}

/**
 * GitHub App 설치 토큰으로 인증한다. 토큰은 메모리에만 두고 [REFRESH_MARGIN] 보다 적게 남았을 때 새로 받는다.
 * 배포가 동시에 여러 개 돌 수 있어 교환은 잠금 안에서 한 번만 한다(먼저 들어온 쪽이 받고 나머지는 그 값을 쓴다).
 */
class GitHubAppCredentials(
    private val appId: String,
    private val privateKey: RSAPrivateKey,
    private val exchange: InstallationTokenExchange,
    private val clock: Clock = Clock.systemUTC(),
) : GitHubCredentials {

    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()

    @Volatile
    private var cached: InstallationToken? = null

    companion object {
        /** 남은 수명이 이보다 적으면 미리 바꾼다(요청 도중에 끝나지 않게). */
        val REFRESH_MARGIN: Duration = Duration.ofMinutes(5)
    }

    override fun authorization(): String = "Bearer ${token()}"

    /** 지금 쓸 설치 토큰. 캐시가 살아 있으면 그대로, 아니면 JWT 로 새로 교환한다. */
    fun token(): String {
        usable()?.let { return it.value }
        return lock.withLock {
            usable()?.let { return@withLock it.value }
            val jwt = GitHubAppJwt.create(appId, privateKey, clock.instant())
            val fresh = exchange.exchange(jwt)
            cached = fresh
            log.info("github app installation token refreshed, expires at {}", fresh.expiresAt)
            fresh.value
        }
    }

    private fun usable(): InstallationToken? =
        cached?.takeIf { it.expiresAt.isAfter(clock.instant().plus(REFRESH_MARGIN)) }
}
