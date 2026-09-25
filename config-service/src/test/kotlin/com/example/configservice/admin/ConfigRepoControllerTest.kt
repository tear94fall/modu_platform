package com.example.configservice.admin

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** 실제 설정 서버 컨텍스트에서 관리 경로가 설정 서버의 `/{name}/{profiles}/{label}` 보다 먼저 맞는지까지 본다. */
@SpringBootTest(
    properties = [
        "modu.internal-api.token=test-token",
        "spring.cloud.config.server.native.search-locations=classpath:/ignored,file:src/test/resources/config-repo",
    ],
)
@AutoConfigureMockMvc
class ConfigRepoControllerTest(@Autowired private val mvc: MockMvc) {

    @Test
    fun `rejects requests without the internal token`() {
        mvc.get("/api-admin/config-repo/files").andExpect { status { isUnauthorized() } }
        mvc.get("/api-admin/config-repo/files") { header("X-Internal-Token", "wrong") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `lists files and returns a masked file`() {
        mvc.get("/api-admin/config-repo/files") { header("X-Internal-Token", "test-token") }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].path") { value("sample.yml") }
            }

        mvc.get("/api-admin/config-repo/file") {
            header("X-Internal-Token", "test-token")
            param("path", "sample.yml")
        }.andExpect {
            status { isOk() }
            jsonPath("$.documents[0].properties[?(@.key == 'sample.password')].value") { value("******") }
            jsonPath("$.documents[0].properties[?(@.key == 'sample.password')].protection") { value("SECRET") }
            jsonPath("$.documents[0].properties[?(@.key == 'sample.name')].value") { value("modu") }
        }
    }

    @Test
    fun `unknown file is 404`() {
        mvc.get("/api-admin/config-repo/file") {
            header("X-Internal-Token", "test-token")
            param("path", "nope.yml")
        }.andExpect { status { isNotFound() } }
    }
}
