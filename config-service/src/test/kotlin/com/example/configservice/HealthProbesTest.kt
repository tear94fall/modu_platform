package com.example.configservice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.HealthEndpointGroups
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** 쿠버네티스 probe·compose healthcheck 가 보는 liveness/readiness 가 열려 있고 UP 인지. */
@SpringBootTest(
    properties = [
        "spring.cloud.config.server.native.search-locations=file:src/test/resources/config-repo",
    ],
)
@AutoConfigureMockMvc
class HealthProbesTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val groups: HealthEndpointGroups,
) {

    @Test
    fun `liveness and readiness are UP without the internal token`() {
        // ConfigServerAuthFilter 는 상태 점검만 열어 둔다. probe 는 토큰 없이 열려야 한다.
        mvc.get("/actuator/health/liveness").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
        mvc.get("/actuator/health/readiness").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `readiness checks the config repository backend`() {
        val readiness = groups.get("readiness")!!
        assertTrue(readiness.isMember("readinessState"))
        assertTrue(readiness.isMember("configServer"))
        val liveness = groups.get("liveness")!!
        assertTrue(liveness.isMember("livenessState"))
        assertFalse(liveness.isMember("configServer"))
    }
}
