package com.example.deployservice.api

import com.example.deployservice.gateway.StaffLookup
import com.example.deployservice.deploy.ArgoCdView
import com.example.deployservice.deploy.DeployMode
import com.example.deployservice.deploy.DeployService
import com.example.deployservice.deploy.DeploymentRecord
import com.example.deployservice.deploy.DeploymentStartedResponse
import com.example.deployservice.deploy.DeploymentStatus
import com.example.deployservice.deploy.DeploymentsResponse
import com.example.deployservice.deploy.ServiceHealth
import com.example.deployservice.deploy.ServiceView
import com.example.deployservice.deploy.ServicesResponse
import com.example.deployservice.deploy.Step
import com.example.deployservice.deploy.TagView
import com.example.deployservice.deploy.TagsResponse
import com.example.deployservice.deploy.TemporalView
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant

/** 컨트롤러 + InternalTokenFilter + 오류 응답. 서비스 계층은 mock. */
@WebMvcTest(DeployController::class, properties = ["modu.internal-api.token=test-token"])
class DeployControllerTest(@Autowired private val mvc: MockMvc) {

    @MockitoBean
    lateinit var service: DeployService

    @MockitoBean
    lateinit var staff: StaffLookup

    private val now = Instant.parse("2026-10-06T12:45:00Z")

    @Test
    fun `rejects requests without or with a wrong internal token`() {
        mvc.get("/api-system/deploy/services").andExpect {
            status { isUnauthorized() }
            jsonPath("$.error") { value("unauthorized") }
            jsonPath("$.message") { exists() }
        }
        mvc.get("/api-system/deploy/services") { header("X-Internal-Token", "wrong") }.andExpect { status { isUnauthorized() } }
        mvc.post("/api-system/deploy/services/point-service") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"tag":"develop-5708871"}"""
        }.andExpect { status { isUnauthorized() } }
        verify(service, never()).services()
        verify(service, never()).deploy(any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `matrix parameter in prefix is still guarded`() {
        // //api-system 과 /%61pi-system 은 MockMvc 가 authority·%25 로 바꿔 버려 InternalTokenFilterTest(서블릿 요청 직접)에서 본다.
        mvc.get("/api-system;x=1/deploy/services").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `lists services with the token`() {
        whenever(service.services()).thenReturn(
            ServicesResponse(
                listOf(ServiceView("point-service", "modu_chat", "ghcr.io/tear94fall/modu-chat/point-service", "develop-5708871", "develop-5708871", 1, 1, 1, ServiceHealth.READY, null)),
                ArgoCdView("modu-dev", "http://localhost:8090/applications/modu-dev"),
                TemporalView("platform", "http://localhost:8233"),
            ),
        )

        mvc.get("/api-system/deploy/services") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.services[0].name") { value("point-service") }
            jsonPath("$.services[0].gitTag") { value("develop-5708871") }
            jsonPath("$.services[0].status") { value("READY") }
            jsonPath("$.services[0].lastDeployment") { value(null) }
            jsonPath("$.argocd.application") { value("modu-dev") }
            jsonPath("$.argocd.url") { value("http://localhost:8090/applications/modu-dev") }
            jsonPath("$.temporal.namespace") { value("platform") }
            jsonPath("$.temporal.url") { value("http://localhost:8233") }
        }
    }

    @Test
    fun `tags are listed and times are ISO-8601 UTC`() {
        whenever(service.tags("point-service")).thenReturn(
            TagsResponse(listOf(TagView("develop-5708871", "5708871", now, "Merge pull request #423", "https://github.com/tear94fall/modu_chat/commit/5708871", true))),
        )

        mvc.get("/api-system/deploy/services/point-service/tags") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isOk() }
            jsonPath("$.tags[0].tag") { value("develop-5708871") }
            jsonPath("$.tags[0].createdAt") { value("2026-10-06T12:45:00Z") }
            jsonPath("$.tags[0].current") { value(true) }
        }
    }

    @Test
    fun `deploy returns 202 and passes the staff identity`() {
        whenever(staff.displayName("104614857372392207989")).thenReturn("임준섭")
        whenever(service.deploy(eq("point-service"), eq("develop-5708871"), eq("임준섭"), eq("104614857372392207989"), eq(DeployMode.DIRECT))).thenReturn(
            DeploymentStartedResponse("dep-20261006-134512-ab12", "point-service", "develop-5708871", "임준섭", "104614857372392207989", now, DeploymentStatus.RUNNING, Step.COMMIT, 5),
        )

        mvc.post("/api-system/deploy/services/point-service") {
            header("X-Internal-Token", "test-token")
            header("X-Auth-User-Id", "104614857372392207989")
            contentType = MediaType.APPLICATION_JSON
            content = """{"tag":"develop-5708871"}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.id") { value("dep-20261006-134512-ab12") }
            jsonPath("$.status") { value("RUNNING") }
            jsonPath("$.step") { value("COMMIT") }
            jsonPath("$.percent") { value(5) }
            jsonPath("$.startedAt") { value("2026-10-06T12:45:00Z") }
        }
    }

    @Test
    fun `deploy without identity header records unknown and without tag is 400`() {
        whenever(service.deploy(eq("point-service"), eq("develop-5708871"), eq("unknown"), eq("unknown"), eq(DeployMode.DIRECT))).thenReturn(
            DeploymentStartedResponse("dep-1", "point-service", "develop-5708871", "unknown", "unknown", now, DeploymentStatus.RUNNING, Step.COMMIT, 5),
        )

        mvc.post("/api-system/deploy/services/point-service") {
            header("X-Internal-Token", "test-token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"tag":"develop-5708871"}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.by") { value("unknown") }
        }

        mvc.post("/api-system/deploy/services/point-service") {
            header("X-Internal-Token", "test-token")
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("invalid_tag") }
        }
    }

    @Test
    fun `service errors become error bodies with their status`() {
        whenever(service.deploy(any(), any(), any(), anyOrNull(), any())).thenThrow(ApiException.conflict("deploy_in_progress", "point-service 는 이미 배포 중입니다."))
        whenever(service.deployment("nope")).thenThrow(ApiException.notFound("deployment_not_found", "배포 기록이 없습니다: nope"))
        whenever(service.tags("point-service")).thenThrow(UpstreamException("github", "GHCR 태그 조회 실패"))

        mvc.post("/api-system/deploy/services/point-service") {
            header("X-Internal-Token", "test-token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"tag":"develop-5708871"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value("deploy_in_progress") }
            jsonPath("$.message") { value("point-service 는 이미 배포 중입니다.") }
        }
        mvc.get("/api-system/deploy/deployments/nope") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("deployment_not_found") }
        }
        mvc.get("/api-system/deploy/services/point-service/tags") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isBadGateway() }
            jsonPath("$.error") { value("github_error") }
        }
    }

    @Test
    fun `deployment detail and history`() {
        val record = DeploymentRecord(id = "dep-1", service = "point-service", tag = "develop-5708871", previousTag = "develop-35db83f", by = "me", startedAt = now)
        whenever(service.deployment("dep-1")).thenReturn(record)
        whenever(service.deployments("point-service", 5)).thenReturn(DeploymentsResponse(listOf(record)))

        mvc.get("/api-system/deploy/deployments/dep-1") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isOk() }
            jsonPath("$.previousTag") { value("develop-35db83f") }
            jsonPath("$.steps[0].name") { value("COMMIT") }
            jsonPath("$.steps[2].status") { value("PENDING") }
            jsonPath("$.rollout") { value(null) }
            jsonPath("$.commit") { value(null) }
            jsonPath("$.error") { value(null) }
            jsonPath("$.finishedAt") { value(null) }
        }
        mvc.get("/api-system/deploy/deployments") {
            header("X-Internal-Token", "test-token")
            param("service", "point-service")
            param("limit", "5")
        }.andExpect {
            status { isOk() }
            jsonPath("$.deployments[0].id") { value("dep-1") }
        }
    }

    @Test
    fun `rollback returns 202`() {
        whenever(service.rollback("point-service", "me", "me")).thenReturn(
            DeploymentStartedResponse("dep-2", "point-service", "develop-35db83f", "me", "me", now, DeploymentStatus.RUNNING, Step.COMMIT, 5),
        )

        mvc.post("/api-system/deploy/services/point-service/rollback") {
            header("X-Internal-Token", "test-token")
            header("X-Auth-User-Id", "me")
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.tag") { value("develop-35db83f") }
        }
    }

    @Test
    fun `deploy passes the canary mode, promote and abort are 202, errors returns the log summary`() {
        whenever(staff.displayName("u1")).thenReturn("me")
        whenever(service.deploy(eq("point-service"), eq("develop-5708871"), eq("me"), eq("u1"), eq(DeployMode.CANARY))).thenReturn(
            DeploymentStartedResponse("dep-c", "point-service", "develop-5708871", "me", "u1", now, DeploymentStatus.RUNNING, Step.COMMIT, 5),
        )
        mvc.post("/api-system/deploy/services/point-service") {
            header("X-Internal-Token", "test-token"); header("X-Auth-User-Id", "u1"); contentType = MediaType.APPLICATION_JSON
            content = """{"tag":"develop-5708871","mode":"CANARY"}"""
        }.andExpect { status { isAccepted() }; jsonPath("$.id") { value("dep-c") } }

        mvc.post("/api-system/deploy/deployments/dep-c/promote") { header("X-Internal-Token", "test-token"); header("X-Auth-User-Id", "u1") }
            .andExpect { status { isAccepted() } }
        verify(service).promote("dep-c", "me")
        mvc.post("/api-system/deploy/deployments/dep-c/abort") { header("X-Internal-Token", "test-token"); header("X-Auth-User-Id", "u1") }
            .andExpect { status { isAccepted() } }
        verify(service).abort("dep-c", "me")

        whenever(service.errors(eq("dep-c"), any())).thenReturn(com.example.deployservice.gateway.ErrorLogs(2, listOf(com.example.deployservice.gateway.LogLine(now, "point-service-new-1", "boom"))))
        mvc.get("/api-system/deploy/deployments/dep-c/errors?since=2026-10-06T12:45:00Z") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isOk() }
            jsonPath("$.count") { value(2) }
            jsonPath("$.lines[0].pod") { value("point-service-new-1") }
            jsonPath("$.lines[0].message") { value("boom") }
        }

        mvc.post("/api-system/deploy/services/point-service/rollout/promote") { header("X-Internal-Token", "test-token"); header("X-Auth-User-Id", "u1") }
            .andExpect { status { isAccepted() } }
        verify(service).promoteRollout("point-service", "me")
        mvc.post("/api-system/deploy/services/point-service/rollout/abort") { header("X-Internal-Token", "test-token"); header("X-Auth-User-Id", "u1") }
            .andExpect { status { isAccepted() } }
        verify(service).abortRollout("point-service", "me")
    }

    @Test
    fun `an unknown mode is a 400`() {
        mvc.post("/api-system/deploy/services/point-service") {
            header("X-Internal-Token", "test-token"); contentType = MediaType.APPLICATION_JSON
            content = """{"tag":"develop-5708871","mode":"BLUE_GREEN"}"""
        }.andExpect { status { isBadRequest() }; jsonPath("$.error") { value("invalid_mode") } }
    }
}
