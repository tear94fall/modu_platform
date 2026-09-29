package com.example.gatewayservice.apidocs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * `modu.api-docs.extra-services`: 라우트의 `lb://` 대상만으로는 빠지는 서비스(이름 → 기본 주소).
 * 라우트가 없거나(chat-store, schedule) Eureka 밖에 있는 서비스도 문서 목록에 넣으려고 둔다.
 */
@ConfigurationProperties("modu.api-docs")
data class ApiDocsProperties(
    val extraServices: Map<String, URI> = emptyMap(),
)
