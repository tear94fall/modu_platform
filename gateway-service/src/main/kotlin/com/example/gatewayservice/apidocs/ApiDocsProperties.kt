package com.example.gatewayservice.apidocs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * `modu.api-docs.extra-services`: 라우트 uri 만으로는 빠지는 서비스(이름 → 주소, 보통 `${modu.services.x}`).
 * 라우트가 없는 서비스(chat-store, schedule)도 문서 목록에 넣으려고 둔다.
 */
@ConfigurationProperties("modu.api-docs")
data class ApiDocsProperties(
    val extraServices: Map<String, URI> = emptyMap(),
)
