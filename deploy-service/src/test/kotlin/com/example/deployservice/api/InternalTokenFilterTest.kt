package com.example.deployservice.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** 필터 단위: 경로 정규화(//, %61, ;x=1, ..)와 토큰 비교. */
class InternalTokenFilterTest {

    private val filter = InternalTokenFilter("secret-token")

    private fun run(method: String, uri: String, token: String? = null): Pair<MockHttpServletResponse, MockFilterChain> {
        val request = MockHttpServletRequest(method, uri)
        if (token != null) request.addHeader(InternalTokenFilter.HEADER, token)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to chain
    }

    @Test
    fun systemPath_withoutOrWrongToken_is401WithErrorBody() {
        val (response, chain) = run("GET", "/api-system/deploy/services")
        assertEquals(401, response.status)
        assertNull(chain.request)
        assertEquals("""{"error":"unauthorized","message":"내부 토큰(X-Internal-Token)이 필요합니다."}""", response.contentAsString)
        assertEquals(401, run("GET", "/api-system/deploy/services", "wrong").first.status)
    }

    @Test
    fun systemPath_withCorrectToken_passesThrough() {
        val (response, chain) = run("POST", "/api-system/deploy/services/point-service", "secret-token")
        assertEquals(200, response.status)
        assertNotNull(chain.request)
    }

    @Test
    fun otherPaths_areNotChecked() {
        assertNotNull(run("GET", "/actuator/health/readiness").second.request)
        assertNotNull(run("GET", "/v3/api-docs").second.request)
        assertNotNull(run("GET", "/api-systemx/deploy").second.request)
    }

    @Test
    fun twistedPaths_areStillGuarded() {
        for (uri in listOf("//api-system/deploy/services", "/%61pi-system/deploy/services", "/api-system;x=1/deploy/services", "/actuator/../api-system/deploy/services", "/api-system")) {
            val (response, chain) = run("GET", uri)
            assertEquals(401, response.status, uri)
            assertNull(chain.request, uri)
        }
    }

    @Test
    fun blankToken_isRejectedAtConstruction() {
        assertThrows(IllegalStateException::class.java) { InternalTokenFilter(" ") }
    }
}
