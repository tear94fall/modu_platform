package com.example.deployservice.github

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 설치 토큰 캐시: 한 번 받아 다시 쓰고, 만료가 가까우면 바꾸고, 동시에 들어와도 한 번만 교환한다. HTTP 는 가짜(호출 수만 센다). */
class GitHubAppCredentialsTest {

    private val privateKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair().private as RSAPrivateKey

    private class MutableClock(var now: Instant = Instant.parse("2026-10-10T09:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    /** JWT 를 받아 토큰을 돌려주는 척한다. 호출마다 다른 값을 주고 [jwts] 에 받은 JWT 를 적는다. */
    private class CountingExchange(val lifetime: Duration = Duration.ofHours(1), val clock: MutableClock) : InstallationTokenExchange {
        val calls = AtomicInteger()
        val jwts = mutableListOf<String>()

        override fun exchange(appJwt: String): InstallationToken {
            val n = calls.incrementAndGet()
            synchronized(jwts) { jwts += appJwt }
            return InstallationToken("ghs_token_$n", clock.instant().plus(lifetime))
        }
    }

    @Test
    fun `token is exchanged once and reused while it is fresh`() {
        val clock = MutableClock()
        val exchange = CountingExchange(clock = clock)
        val credentials = GitHubAppCredentials("1234", privateKey, exchange, clock)

        assertEquals("Bearer ghs_token_1", credentials.authorization())
        clock.now = clock.now.plus(Duration.ofMinutes(50))
        assertEquals("Bearer ghs_token_1", credentials.authorization())

        assertEquals(1, exchange.calls.get())
        assertEquals(3, exchange.jwts.single().split('.').size, "교환에는 App JWT 를 쓴다")
    }

    @Test
    fun `token is refreshed when less than five minutes remain`() {
        val clock = MutableClock()
        val exchange = CountingExchange(clock = clock)
        val credentials = GitHubAppCredentials("1234", privateKey, exchange, clock)

        assertEquals("Bearer ghs_token_1", credentials.authorization())
        clock.now = clock.now.plus(Duration.ofMinutes(56)) // 남은 수명 4분 < 여유 5분
        assertEquals("Bearer ghs_token_2", credentials.authorization())
        assertEquals(2, exchange.calls.get())
    }

    @Test
    fun `concurrent callers trigger a single exchange`() {
        val clock = MutableClock()
        val exchange = CountingExchange(clock = clock)
        val credentials = GitHubAppCredentials("1234", privateKey, exchange, clock)
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)

        val values = (1..threads)
            .map { pool.submit(Callable { barrier.await(5, TimeUnit.SECONDS); credentials.authorization() }) }
            .map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, exchange.calls.get(), "동시에 들어와도 교환은 한 번")
        assertTrue(values.all { it == "Bearer ghs_token_1" }, values.toString())
    }

    @Test
    fun `legacy token and anonymous credentials pick the right header`() {
        assertEquals("token ghp_legacy", GitHubCredentials.legacyToken("ghp_legacy").authorization())
        assertEquals(null, GitHubCredentials.ANONYMOUS.authorization())
    }

    @Test
    fun `the token value never shows up in toString`() {
        val token = InstallationToken("ghs_secret", Instant.parse("2026-10-10T10:00:00Z"))
        assertTrue(!token.toString().contains("ghs_secret"), token.toString())
    }
}
