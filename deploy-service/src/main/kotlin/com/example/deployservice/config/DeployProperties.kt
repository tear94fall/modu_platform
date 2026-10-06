package com.example.deployservice.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * `deploy.*` — config-repo/deploy-service.yml 이 내려준다(토큰은 {cipher}, config-service 가 평문으로 복호화).
 *
 * - github: modu_infra 의 kustomization.yaml 을 읽고 커밋하는 저장소·브랜치·경로, GHCR 패키지·커밋을 읽는 토큰.
 * - argocd: Sync 를 부를 Argo CD 주소·Application·API 토큰. publicUrl 은 콘솔이 여는 링크(없으면 url).
 * - kubernetes: Deployment 가 있는 네임스페이스.
 * - services: 배포 탭에 보이는 서비스. name = k8s Deployment 이름, image = kustomization images[].name.
 * - store: 배포 이력 목록(list)이 한 번에 돌려주는 최대 건수(DB 에는 전부 남는다).
 */
@ConfigurationProperties(prefix = "deploy")
data class DeployProperties(
    val github: GitHub = GitHub(),
    val argocd: ArgoCd = ArgoCd(),
    val kubernetes: Kubernetes = Kubernetes(),
    val services: List<ServiceSpec> = emptyList(),
    val store: Store = Store(),
) {
    data class GitHub(
        val apiUrl: String = "https://api.github.com",
        val owner: String = "",
        val infraRepo: String = "",
        val infraBranch: String = "main",
        val kustomizationPath: String = "",
        val token: String = "",
    ) {
        /** 토큰이 로그·toString 에 실리지 않게. */
        override fun toString(): String =
            "GitHub(apiUrl=$apiUrl, owner=$owner, infraRepo=$infraRepo, infraBranch=$infraBranch, kustomizationPath=$kustomizationPath, token=***)"
    }

    data class ArgoCd(
        val url: String = "",
        val application: String = "",
        val token: String = "",
        val publicUrl: String? = null,
    ) {
        /** 콘솔이 여는 Application 화면 주소. */
        fun applicationUrl(): String = "${(publicUrl?.takeIf { it.isNotBlank() } ?: url).trimEnd('/')}/applications/$application"

        override fun toString(): String = "ArgoCd(url=$url, application=$application, publicUrl=$publicUrl, token=***)"
    }

    data class Kubernetes(val namespace: String = "modu")

    data class ServiceSpec(val name: String, val repo: String, val image: String)

    data class Store(val capacity: Int = 500)

    fun service(name: String): ServiceSpec? = services.firstOrNull { it.name == name }
}

@Configuration
@EnableConfigurationProperties(DeployProperties::class)
class DeployPropertiesConfig
