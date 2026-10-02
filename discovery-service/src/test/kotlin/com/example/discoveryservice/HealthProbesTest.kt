package com.example.discoveryservice

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
@SpringBootTest
@AutoConfigureMockMvc
class HealthProbesTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val groups: HealthEndpointGroups,
) {

    @Test
    fun `liveness and readiness are UP`() {
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
    fun `readiness depends only on the application state`() {
        val readiness = groups.get("readiness")!!
        assertTrue(readiness.isMember("readinessState"))
        // Eureka 클라이언트 indicator(discoveryComposite 등)가 readiness 를 흔들면 안 된다.
        assertFalse(readiness.isMember("discoveryComposite"))
        assertTrue(groups.get("liveness")!!.isMember("livenessState"))
    }
}
