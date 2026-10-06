package com.example.deployservice.api

import com.example.deployservice.gateway.StaffLookup
import com.example.deployservice.deploy.DeployService
import com.example.deployservice.deploy.DeploymentRecord
import com.example.deployservice.deploy.DeploymentStartedResponse
import com.example.deployservice.deploy.DeploymentsResponse
import com.example.deployservice.deploy.ServicesResponse
import com.example.deployservice.deploy.TagsResponse
import com.example.deployservice.logging.RequestContextFilter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class DeployRequest(val tag: String?)

/**
 * 모두 시스템 "배포" 탭. 게이트웨이 라우트 deploy-service-system(`/deploy-service/api-system/...`, ROLE_SYSTEM)이
 * 내부 토큰과 직원 식별(X-Auth-User-Id)을 붙여 넘긴다. 토큰은 [InternalTokenFilter] 가 본다.
 */
@RestController
@RequestMapping("/api-system/deploy")
@Tag(name = "deploy", description = "서비스 배포(GHCR 태그 → modu_infra 커밋 → Argo CD Sync → 롤아웃)")
class DeployController(private val service: DeployService, private val staff: StaffLookup) {

    companion object {
        const val UNKNOWN_USER = "unknown"
    }

    @Operation(summary = "배포 가능한 서비스와 현재 태그·롤아웃 상태")
    @GetMapping("/services")
    fun services(): ServicesResponse = service.services()

    @Operation(summary = "서비스의 GHCR 커밋 태그(최신순, 최대 30)")
    @GetMapping("/services/{name}/tags")
    fun tags(@PathVariable name: String): TagsResponse = service.tags(name)

    @Operation(summary = "태그 배포 시작(비동기, 202)")
    @PostMapping("/services/{name}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun deploy(
        @PathVariable name: String,
        @RequestBody(required = false) body: DeployRequest?,
        @RequestHeader(RequestContextFilter.USER_ID_HEADER, required = false) userId: String?,
    ): DeploymentStartedResponse {
        val tag = body?.tag?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ApiException.badRequest("invalid_tag", "배포할 태그(tag)를 보내 주세요.")
        val id = by(userId)
        return service.deploy(name, tag, displayName(id), id)
    }

    @Operation(summary = "마지막 성공 배포의 이전 태그로 되돌리기(비동기, 202)")
    @PostMapping("/services/{name}/rollback")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun rollback(
        @PathVariable name: String,
        @RequestHeader(RequestContextFilter.USER_ID_HEADER, required = false) userId: String?,
    ): DeploymentStartedResponse = by(userId).let { service.rollback(name, displayName(it), it) }

    @Operation(summary = "배포 한 건의 진행 상황")
    @GetMapping("/deployments/{id}")
    fun deployment(@PathVariable id: String): DeploymentRecord = service.deployment(id)

    @Operation(summary = "배포 이력(메모리, 최신순)")
    @GetMapping("/deployments")
    fun deployments(
        @RequestParam(required = false) service: String?,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
    ): DeploymentsResponse = this.service.deployments(service, limit)

    private fun by(userId: String?): String = userId?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_USER

    /** 표시 이름(member-service 의 username/email). 못 풀면 식별자 그대로. */
    private fun displayName(id: String): String = if (id == UNKNOWN_USER) id else staff.displayName(id) ?: id
}
