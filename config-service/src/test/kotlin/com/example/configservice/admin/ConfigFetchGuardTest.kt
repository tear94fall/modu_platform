package com.example.configservice.admin

import java.util.Base64
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 설정 조회(/{app}/{profile})는 {cipher} 가 풀린 평문을 돌려준다 — 인증 없이 열려 있으면 DB 비밀번호·내부 토큰·외부 토큰이 그대로 나간다.
 * 각 서비스(Spring Cloud Config 클라이언트)는 설정을 받기 전이라 토큰을 환경변수로 받아 HTTP Basic 의 비밀번호로 보낸다.
 * 쿠버네티스가 부르는 상태 점검만 열어 둔다(토큰을 넣을 수 없는 경로).
 */
@SpringBootTest(
    properties = [
        "modu.internal-api.token=test-token",
        "encrypt.key=test-encrypt-key",
        "spring.cloud.config.server.native.search-locations=file:src/test/resources/config-repo",
    ],
)
@AutoConfigureMockMvc
class ConfigFetchGuardTest(@Autowired private val mvc: MockMvc) {

    private fun basic(user: String, password: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    @Test
    fun `config fetch is rejected without credentials`() {
        mvc.perform(get("/some-service/default")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `config fetch is allowed with the internal token header`() {
        mvc.perform(get("/some-service/default").header(ConfigServerAuthFilter.HEADER, "test-token"))
            .andExpect(status().isOk)
    }

    @Test
    fun `config fetch is allowed with basic auth whose password is the token`() {
        // 사용자 이름은 보지 않는다 — 클라이언트가 spring.cloud.config.username 에 무엇을 넣든 비밀번호만 맞으면 된다.
        mvc.perform(get("/some-service/default").header(HttpHeaders.AUTHORIZATION, basic("modu", "test-token")))
            .andExpect(status().isOk)
    }

    @Test
    fun `basic auth with a wrong password is rejected`() {
        mvc.perform(get("/some-service/default").header(HttpHeaders.AUTHORIZATION, basic("modu", "nope")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `refresh endpoints are closed without credentials`() {
        // 리로드를 아무나 걸 수 있으면 설정을 흔들 수 있다.
        mvc.perform(post("/actuator/refresh")).andExpect(status().isUnauthorized)
        mvc.perform(post("/actuator/busrefresh")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `health probes stay open`() {
        // 쿠버네티스 probe 는 헤더를 넣을 수 없다. 묶음 /actuator/health 는 이 테스트 환경에서 UP 이 아니라 여기서 보지 않는다
        // (열려 있는지·UP 인지는 HealthProbesTest 가 본다).
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk)
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk)
    }
}
