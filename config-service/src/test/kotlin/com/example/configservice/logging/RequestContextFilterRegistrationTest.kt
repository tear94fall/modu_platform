package com.example.configservice.logging

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** 실제 컨텍스트에서 필터가 등록돼 모든 응답(내부 토큰 없이 거절되는 것 포함)에 X-Request-Id 가 실리는지. */
@SpringBootTest(
    properties = [
        "spring.cloud.config.server.native.search-locations=file:src/test/resources/config-repo",
    ],
)
@AutoConfigureMockMvc
class RequestContextFilterRegistrationTest(@Autowired private val mvc: MockMvc) {

    @Test
    fun `every response carries X-Request-Id`() {
        mvc.get("/api-admin/config-repo/files") { header("X-Request-Id", "gw-1") }.andExpect {
            status { isUnauthorized() }
            header { string("X-Request-Id", "gw-1") }
        }
        mvc.get("/actuator/health/liveness").andExpect {
            status { isOk() }
            header { exists("X-Request-Id") }
        }
    }
}
