package com.example.deployservice.github

import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * GitHub App 의 비밀키(PEM)를 읽는다. 기동 때 한 번만 부르고, 잘못된 키면 **바로 기동을 멈춘다**
 * (배포 버튼을 눌렀을 때 처음 알게 되면 늦다).
 *
 * 받는 모양:
 * - PKCS#8 PEM(`-----BEGIN PRIVATE KEY-----`, 줄바꿈 있어도 됨) — 권장.
 * - 위 PEM 의 본문(base64)을 줄바꿈 없이 한 줄로 붙인 값 — YAML 한 줄에 넣기 쉬우라고.
 * - 위 PEM 전체를 다시 base64 로 감싼 값(한 겹만) — `base64 -i app.pk8.pem` 결과.
 *
 * GitHub 가 내려주는 파일은 PKCS#1(`-----BEGIN RSA PRIVATE KEY-----`)인데 JDK 의 KeyFactory 는 읽지 못한다.
 * 그 경우 변환 명령을 적은 한국어 메시지로 실패한다.
 */
object GitHubAppPrivateKeys {

    private const val PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----"
    private const val PKCS8_END = "-----END PRIVATE KEY-----"
    private const val PKCS1_BEGIN = "BEGIN RSA PRIVATE KEY"
    private const val ENCRYPTED_BEGIN = "BEGIN ENCRYPTED PRIVATE KEY"

    const val CONVERT_HINT =
        "GitHub 가 준 .pem 은 PKCS#1 이라 JDK 가 읽지 못합니다. " +
            "`openssl pkcs8 -topk8 -nocrypt -in app.pem -out app.pk8.pem` 로 바꾼 뒤 그 내용을 deploy.github.app.private-key 에 넣으세요."

    /** PEM(또는 한 줄 base64)을 RSA 비밀키로. 못 읽으면 [IllegalStateException]. */
    fun parse(pem: String): RSAPrivateKey = parse(pem, unwrapped = false)

    private fun parse(pem: String, unwrapped: Boolean): RSAPrivateKey {
        val text = pem.trim()
        check(text.isNotBlank()) { "deploy.github.app.private-key 가 비어 있습니다 — GitHub App 비밀키(PKCS#8 PEM)를 내려줘야 합니다." }
        check(!text.contains(PKCS1_BEGIN)) { CONVERT_HINT }
        check(!text.contains(ENCRYPTED_BEGIN)) {
            "deploy.github.app.private-key 가 암호로 잠긴 키입니다 — `openssl pkcs8 -topk8 -nocrypt` 로 암호를 푼 PKCS#8 키를 넣으세요."
        }

        val body = if (text.contains(PKCS8_BEGIN)) {
            text.substringAfter(PKCS8_BEGIN).substringBefore(PKCS8_END)
        } else {
            text
        }
        val der = runCatching { Base64.getMimeDecoder().decode(body.filterNot { it.isWhitespace() }) }
            .getOrElse { throw IllegalStateException("deploy.github.app.private-key 를 base64 로 읽을 수 없습니다 — PKCS#8 PEM 이거나 그 본문 한 줄이어야 합니다.", it) }

        // PEM 전체를 base64 로 감싼 값이면 한 겹 벗겨 다시 읽는다(두 겹은 보지 않는다).
        if (!unwrapped && !text.contains(PKCS8_BEGIN)) {
            val decoded = String(der, Charsets.US_ASCII)
            if (decoded.contains("-----BEGIN")) return parse(decoded, unwrapped = true)
        }

        return runCatching { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey }
            .getOrElse { throw IllegalStateException("deploy.github.app.private-key 를 RSA 비밀키로 읽을 수 없습니다. $CONVERT_HINT", it) }
    }
}
