package com.example.configservice.admin

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** /encrypt·/decrypt 는 내부 토큰 없이는 닫혀 있어야 한다 — /decrypt 가 열리면 config-repo 의 {cipher} 를 누구나 풀 수 있다. */
@SpringBootTest(
    properties = [
        "modu.internal-api.token=test-token",
        "encrypt.key=test-encrypt-key",
        "spring.cloud.config.server.native.search-locations=classpath:/ignored,file:src/test/resources/config-repo",
    ],
)
@AutoConfigureMockMvc
class EncryptEndpointGuardTest(@Autowired private val mvc: MockMvc) {

    @Test
    fun `encrypt and decrypt reject requests without the token`() {
        mvc.perform(post("/encrypt").content("secret")).andExpect(status().isUnauthorized)
        mvc.perform(post("/decrypt").content("00")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `encrypt then decrypt round-trips with the token`() {
        val cipher = mvc.perform(post("/encrypt").header(ConfigServerAuthFilter.HEADER, "test-token").contentType(MediaType.TEXT_PLAIN).content("secret"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        mvc.perform(post("/decrypt").header(ConfigServerAuthFilter.HEADER, "test-token").contentType(MediaType.TEXT_PLAIN).content(cipher))
            .andExpect(status().isOk).andExpect(content().string("secret"))
    }
}
