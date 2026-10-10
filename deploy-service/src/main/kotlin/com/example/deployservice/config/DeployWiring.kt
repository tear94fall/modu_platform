package com.example.deployservice.config

import org.springframework.beans.factory.annotation.Value
import com.example.deployservice.gateway.MemberServiceStaffLookup
import com.example.deployservice.gateway.StaffLookup
import com.example.deployservice.deploy.DefaultDeployService
import com.example.deployservice.deploy.DeployService
import com.example.deployservice.deploy.DeploymentExecutor
import com.example.deployservice.deploy.DeploymentRunner
import com.example.deployservice.deploy.DeploymentStore
import com.example.deployservice.gateway.ArgoCdGateway
import com.example.deployservice.gateway.ArgoCdRestGateway
import com.example.deployservice.gateway.ContainerRegistryGateway
import com.example.deployservice.gateway.Fabric8KubernetesGateway
import com.example.deployservice.gateway.GhcrRegistryGateway
import com.example.deployservice.gateway.GitHubGateway
import com.example.deployservice.gateway.GitHubRestGateway
import com.example.deployservice.gateway.KubernetesGateway
import com.example.deployservice.github.GitHubAppCredentials
import com.example.deployservice.github.GitHubAppPrivateKeys
import com.example.deployservice.github.GitHubCredentials
import com.example.deployservice.github.RestClientInstallationTokens
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration

/**
 * 외부 시스템 클라이언트와 배포 파이프라인 빈. 게이트웨이(GitHub·GHCR·Argo CD·k8s)는 인터페이스로 두어 테스트가 가짜로 바꿔 끼운다.
 * 자격(GitHub App 비밀키·Argo CD 토큰)은 config-repo/deploy-service.yml 의 {cipher} 값(config-service 가 복호화)이고 로그에 남기지 않는다.
 */
@Configuration
class DeployWiring {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** GHCR 레지스트리 주소(이미지 pull 과 같은 호스트). */
        const val GHCR_URL = "https://ghcr.io"

        /**
         * GitHub API 클라이언트. `Authorization` 은 **요청마다** [credentials] 에 묻는다(없으면 헤더를 넣지 않는다 = 익명):
         * 설치 토큰은 한 시간이면 끝나므로 클라이언트를 만들 때 한 번 박아 둘 수 없다.
         * 빌더로 돌려주는 건 테스트가 MockRestServiceServer 를 붙일 수 있게 하려는 것이다.
         */
        fun gitHubClientBuilder(apiUrl: String, credentials: GitHubCredentials): RestClient.Builder = RestClient.builder()
            .baseUrl(apiUrl.trimEnd('/'))
            .requestInitializer { request -> credentials.authorization()?.let { request.headers.set(HttpHeaders.AUTHORIZATION, it) } }
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")

        /** GHCR 레지스트리 클라이언트 — GitHub 자격을 붙이지 않는다(요청마다 익명 pull 토큰을 받는다). */
        fun ghcrClientBuilder(registryUrl: String = GHCR_URL): RestClient.Builder = RestClient.builder().baseUrl(registryUrl.trimEnd('/'))
    }

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /**
     * GitHub 자격을 고른다 — 앱(`deploy.github.app.id`) → 레거시 토큰(`deploy.github.token`) → 익명.
     * 비밀키는 **여기서(기동 때)** 읽는다: PKCS#1 PEM 같은 잘못된 키면 배포 버튼을 눌렀을 때가 아니라 바로 기동이 실패한다.
     */
    @Bean
    fun gitHubCredentials(properties: DeployProperties, clock: Clock): GitHubCredentials {
        val gh = properties.github
        if (!gh.app.enabled) {
            if (gh.token.isBlank()) {
                log.warn("deploy.github 자격이 없습니다 — 공개 저장소 읽기만 익명으로 됩니다(쓰기·배포는 실패).")
                return GitHubCredentials.ANONYMOUS
            }
            log.warn("deploy.github.token(레거시 PAT)으로 인증합니다 — GitHub App 으로 옮긴 뒤 이 값을 지우세요.")
            return GitHubCredentials.legacyToken(gh.token)
        }
        val key = GitHubAppPrivateKeys.parse(gh.app.privateKey)
        val appClient = RestClient.builder()
            .baseUrl(gh.apiUrl.trimEnd('/'))
            .requestFactory(requestFactory())
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build()
        log.info(
            "github app {} 자격을 씁니다(installation {}).",
            gh.app.id,
            gh.app.installationId.takeIf { it.isNotBlank() } ?: "미지정 — GET /app/installations 로 찾습니다",
        )
        return GitHubAppCredentials(gh.app.id, key, RestClientInstallationTokens(appClient, gh.app.installationId), clock)
    }

    @Bean
    fun gitHubGateway(properties: DeployProperties, credentials: GitHubCredentials): GitHubGateway {
        val gh = properties.github
        return GitHubRestGateway(gitHubClientBuilder(gh.apiUrl, credentials).requestFactory(requestFactory()).build(), gh.owner)
    }

    /**
     * GHCR 태그 조회. **GitHub 자격을 쓰지 않는다** — 저장소가 public 이라 레지스트리 익명 pull 토큰으로 읽는다
     * (GitHub Packages REST API 는 공개 패키지라도 인증을 요구하고 App·fine-grained 토큰으로는 쓸 수 없다).
     */
    @Bean
    fun containerRegistryGateway(properties: DeployProperties, clock: Clock): ContainerRegistryGateway =
        GhcrRegistryGateway(ghcrClientBuilder().requestFactory(requestFactory()).build(), properties.github.owner, clock)

    @Bean
    fun argoCdGateway(properties: DeployProperties): ArgoCdGateway {
        val argo = properties.argocd
        val client = RestClient.builder()
            .baseUrl(argo.url.trimEnd('/'))
            .requestFactory(requestFactory())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${argo.token}")
            .build()
        return ArgoCdRestGateway(client)
    }

    /** 기본 설정: 파드 안에선 ServiceAccount(토큰·CA 자동), 로컬에선 ~/.kube/config. 만들 때 접속하지 않는다. */
    @Bean(destroyMethod = "close")
    fun kubernetesClient(): KubernetesClient = KubernetesClientBuilder().build()

    @Bean
    fun kubernetesGateway(client: KubernetesClient, properties: DeployProperties): KubernetesGateway =
        Fabric8KubernetesGateway(client, properties.kubernetes.namespace)

    @Bean
    fun deploymentExecutor(
        properties: DeployProperties,
        github: GitHubGateway,
        argo: ArgoCdGateway,
        kubernetes: KubernetesGateway,
        store: DeploymentStore,
        clock: Clock,
    ): DeploymentExecutor = DeploymentExecutor(properties, github, argo, kubernetes, store, clock)

    @Bean
    fun deploymentRunner(store: DeploymentStore, executor: DeploymentExecutor): DeploymentRunner = DeploymentRunner(store, executor)

    @Bean
    fun deployService(
        properties: DeployProperties,
        github: GitHubGateway,
        registry: ContainerRegistryGateway,
        kubernetes: KubernetesGateway,
        store: DeploymentStore,
        runner: DeploymentRunner,
        clock: Clock,
    ): DeployService = DefaultDeployService(properties, github, registry, kubernetes, store, runner, clock)

    private fun requestFactory() = SimpleClientHttpRequestFactory().apply {
        setConnectTimeout(Duration.ofSeconds(10))
        setReadTimeout(Duration.ofSeconds(30))
    }

    /** 배포자 이름 조회용 member-service 클라이언트(주소 modu.services.member-service, 내부 토큰). */
    @Bean
    fun staffLookup(
        @Value("\${modu.services.member-service}") memberServiceUrl: String,
        @Value("\${modu.internal-api.token}") internalToken: String,
    ): StaffLookup = MemberServiceStaffLookup(
        RestClient.builder().baseUrl(memberServiceUrl.trimEnd('/')).defaultHeader("X-Internal-Token", internalToken).build(),
    )
}
