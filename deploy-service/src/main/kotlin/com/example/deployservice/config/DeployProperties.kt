package com.example.deployservice.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * `deploy.*` — config-repo/deploy-service.yml 이 내려준다(토큰은 {cipher}, config-service 가 평문으로 복호화).
 *
 * - github: modu_infra 의 kustomization.yaml 을 읽고 커밋하는 저장소·브랜치·경로와 그 자격(GitHub App, 또는 레거시 토큰).
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
    /**
     * [app] 이 있으면(id 가 비어 있지 않으면) GitHub App 설치 토큰으로 인증한다 — 한 시간이면 끝나고 설치된 저장소에만 닿는다.
     * [token] 은 **마이그레이션 동안만** 남겨 둔 레거시 개인 액세스 토큰(App 이 생기기 전에도 돌게). App 이 서면 지운다.
     * GHCR 태그는 둘 중 어느 것도 쓰지 않는다 — 레지스트리 API 로 익명 조회한다([com.example.deployservice.gateway.GhcrRegistryGateway]).
     */
    data class GitHub(
        val apiUrl: String = "https://api.github.com",
        val owner: String = "",
        val infraRepo: String = "",
        val infraBranch: String = "main",
        val kustomizationPath: String = "",
        val token: String = "",
        val app: App = App(),
    ) {
        /**
         * GitHub App 자격. [id] 는 App ID(또는 Client ID), [privateKey] 는 PKCS#8 PEM(또는 그 본문 한 줄 base64 —
         * [com.example.deployservice.github.GitHubAppPrivateKeys]). 필요한 권한은 Repository → Contents: Read and write
         * (+ Metadata: Read, 자동)뿐이고 설치 대상은 modu_infra 뿐이다.
         *
         * [installationId] 는 **선택**이다 — 비우면 기동 뒤 첫 호출 때 `GET /app/installations` 로 찾아 기억한다.
         * 설치가 여러 개가 될 때만 적어 주면 된다.
         */
        data class App(
            val id: String = "",
            val installationId: String = "",
            val privateKey: String = "",
        ) {
            /**
             * 둘 다 있어야 앱 경로를 쓴다. 비밀키는 공개 저장소인 config-repo 가 아니라 k8s Secret → 환경변수로 들어오므로
             * (DEPLOY_GITHUB_APP_PRIVATE_KEY), 시크릿을 아직 안 넣은 환경에서 id 만 보고 기동이 실패하면 안 된다.
             */
            val enabled: Boolean get() = id.isNotBlank() && privateKey.isNotBlank()

            /** 비밀키가 로그·toString 에 실리지 않게. */
            override fun toString(): String = "App(id=$id, installationId=$installationId, privateKey=***)"
        }

        /** 토큰·비밀키가 로그·toString 에 실리지 않게. */
        override fun toString(): String =
            "GitHub(apiUrl=$apiUrl, owner=$owner, infraRepo=$infraRepo, infraBranch=$infraBranch, " +
                "kustomizationPath=$kustomizationPath, token=***, app=$app)"
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
