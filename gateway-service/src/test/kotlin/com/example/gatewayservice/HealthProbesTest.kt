package com.example.gatewayservice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.HealthEndpointGroups
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * 쿠버네티스 probe·compose healthcheck 가 보는 게이트웨이 자신의 liveness/readiness 가 열려 있고 UP 인지.
 * 보안 체인(permitAll)·관리 컨트롤러(/gateway-service/api-admin)·라우트 어느 것도 `/actuator/health` 아래를 막지 않아야 한다.
 */
@SpringBootTest(
    properties = [
        "modu.internal-api.token=test-internal-token",
        "modu.oauth.jwks-uri=http://localhost:9900/oauth2/jwks",
        "modu.oauth.issuer=http://localhost:8000/auth-service",
    ],
)
@AutoConfigureWebTestClient
class HealthProbesTest {

    @Autowired lateinit var client: WebTestClient

    @Autowired lateinit var groups: HealthEndpointGroups

    @Test
    fun livenessAndReadiness_areUpWithoutAnyToken() {
        client.get().uri("/actuator/health/liveness").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("UP")
        client.get().uri("/actuator/health/readiness").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("UP")
    }

    @Test
    fun readiness_dependsOnlyOnReadinessState() {
        val readiness = groups.get("readiness")!!
        assertTrue(readiness.isMember("readinessState"))
        // 뒤 서비스가 떠 있는지(modu.services 주소)나 설정 서버 연결은 readiness 조건이 아니다.
        assertFalse(readiness.isMember("configServer"))
        assertTrue(groups.get("liveness")!!.isMember("livenessState"))
    }
}
