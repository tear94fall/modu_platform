package com.example.gatewayservice.apidocs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * `modu.services.<이름>: http://<호스트>:<포트>` — 게이트웨이가 부르는 서비스 주소(config-repo 의 application.yml 이 내려준다).
 * 라우트 uri 가 `${modu.services.x}` 로 이 값을 쓰고, API 문서 목록은 라우트 uri 를 이 표에서 거꾸로 찾아 서비스 이름을 얻는다.
 */
@ConfigurationProperties("modu")
data class ServiceAddresses(
    val services: Map<String, URI> = emptyMap(),
) {
    /** [uri] 가 가리키는 서비스 이름(소문자 키). 스킴·호스트·포트가 같으면 같은 서비스로 본다. */
    fun nameOf(uri: URI?): String? {
        if (uri == null) return null
        val key = key(uri) ?: return null
        return services.entries.firstOrNull { key(it.value) == key }?.key?.lowercase()
    }

    companion object {
        /** 비교용 `scheme://host:port`. 호스트 없는(불투명) URI 는 null. */
        fun key(uri: URI): String? {
            val host = uri.host ?: return null
            return "${uri.scheme.lowercase()}://${host.lowercase()}:${uri.port}"
        }
    }
}
