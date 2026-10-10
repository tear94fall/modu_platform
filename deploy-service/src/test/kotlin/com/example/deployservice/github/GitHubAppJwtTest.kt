package com.example.deployservice.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Base64

/**
 * App JWT 를 라이브러리 없이(java.security) 만든다 — 모양(헤더·클레임)과 **서명이 짝 공개키로 검증되는지**를 본다.
 * 키는 테스트에서 만든다(네트워크·고정 키 없음).
 */
class GitHubAppJwtTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
    private val privateKey = keyPair.private as RSAPrivateKey
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-10-10T09:00:00Z")

    private fun part(token: String, index: Int): JsonNode =
        mapper.readTree(Base64.getUrlDecoder().decode(token.split('.')[index]))

    @Test
    fun `header and claims carry RS256 the app id and a nine minute expiry`() {
        val token = GitHubAppJwt.create("Iv23li9zAbCdEf", privateKey, now)

        assertEquals(3, token.split('.').size)
        assertEquals("RS256", part(token, 0).path("alg").asText())
        assertEquals("JWT", part(token, 0).path("typ").asText())
        val claims = part(token, 1)
        assertEquals("Iv23li9zAbCdEf", claims.path("iss").asText())
        assertEquals(now.epochSecond - 60, claims.path("iat").asLong(), "iat 는 시계 차이를 보고 60초 과거")
        assertEquals(now.epochSecond + 9 * 60, claims.path("exp").asLong(), "exp 는 GitHub 상한(10분)보다 짧은 9분")
        assertTrue(token.none { it == '+' || it == '/' || it == '=' }, "base64url(패딩 없음)이어야 한다: $token")
    }

    @Test
    fun `signature verifies with the matching public key and fails with another`() {
        val token = GitHubAppJwt.create("12345", privateKey, now)
        val signingInput = token.substringBeforeLast('.').toByteArray(Charsets.US_ASCII)
        val signature = Base64.getUrlDecoder().decode(token.substringAfterLast('.'))

        val good = Signature.getInstance("SHA256withRSA").apply { initVerify(keyPair.public); update(signingInput) }
        assertTrue(good.verify(signature), "짝 공개키로 검증돼야 한다")

        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair().public
        val bad = Signature.getInstance("SHA256withRSA").apply { initVerify(otherKey); update(signingInput) }
        assertTrue(!bad.verify(signature), "다른 키로는 검증되면 안 된다")
    }

    @Test
    fun `pkcs8 pem single line base64 and base64 wrapped pem are all accepted`() {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privateKey.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"

        val fromPem = GitHubAppPrivateKeys.parse(pem)
        val fromOneLine = GitHubAppPrivateKeys.parse(body.replace("\n", ""))
        val fromWrapped = GitHubAppPrivateKeys.parse(Base64.getEncoder().encodeToString(pem.toByteArray()))

        assertEquals(privateKey.modulus, fromPem.modulus)
        assertEquals(privateKey.modulus, fromOneLine.modulus)
        assertEquals(privateKey.modulus, fromWrapped.modulus)
        // 세 모양 모두 같은 서명을 낸다.
        assertEquals(GitHubAppJwt.create("1", fromPem, now), GitHubAppJwt.create("1", fromOneLine, now))
    }

    @Test
    fun `pkcs1 pem fails fast with the openssl conversion hint`() {
        val pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nMIIEogIBAAKCAQEA\n-----END RSA PRIVATE KEY-----"

        val e = assertThrows(IllegalStateException::class.java) { GitHubAppPrivateKeys.parse(pkcs1) }

        assertTrue(e.message!!.contains("openssl pkcs8 -topk8 -nocrypt"), e.message)
        assertTrue(e.message!!.contains("PKCS#1"), e.message)
    }

    @Test
    fun `blank and unreadable keys fail with a korean message`() {
        assertTrue(assertThrows(IllegalStateException::class.java) { GitHubAppPrivateKeys.parse("  ") }.message!!.contains("비어 있습니다"))
        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                GitHubAppPrivateKeys.parse("-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----")
            }.message!!.contains("암호로 잠긴"),
        )
        assertTrue(
            assertThrows(IllegalStateException::class.java) { GitHubAppPrivateKeys.parse("not-a-key!!!") }.message!!.isNotBlank(),
        )
    }
}
