package com.example.batchservice

import com.example.batchservice.config.BatchProperties
import com.example.batchservice.config.ModuProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** 전체 컨텍스트: 설정 바인딩(batch.*, modu.*), probe, API 문서, 토큰 필터. 배치 DB 는 H2(master·replica 같은 DB), 설정 서버 없음. */
@SpringBootTest
@AutoConfigureMockMvc
class BatchServiceApplicationTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val groups: HealthEndpointGroups,
    @Autowired private val batch: BatchProperties,
    @Autowired private val modu: ModuProperties,
) {

    @Test
    fun `binds batch and modu properties`() {
        assertEquals(600, batch.run.defaultTimeoutSeconds)
        assertEquals(5, batch.scheduler.poolSize)
        assertEquals("http://localhost:13", modu.serviceUrl("commerce-service"))
        assertFalse(modu.internalApi.toString().contains("test-token"))
    }

    @Test
    fun `liveness and readiness are UP and readiness watches the master db only`() {
        mvc.get("/actuator/health/liveness").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
        mvc.get("/actuator/health/readiness").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
        val readiness = groups.get("readiness")!!
        assertTrue(readiness.isMember("masterDb"))
        assertFalse(readiness.isMember("db"))
    }

    @Test
    fun `api docs are open`() {
        mvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.info.title") { value("batch-service") }
            jsonPath("$.paths['/actuator/health']") { doesNotExist() }
        }
    }

    @Test
    fun `system api needs the internal token`() {
        mvc.get("/api-system/batch/jobs").andExpect { status { isUnauthorized() } }
    }
}
