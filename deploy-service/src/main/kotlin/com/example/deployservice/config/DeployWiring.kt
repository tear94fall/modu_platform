package com.example.deployservice.config

import org.springframework.beans.factory.annotation.Value
import com.example.deployservice.gateway.MemberServiceStaffLookup
import com.example.deployservice.gateway.StaffLookup
import com.example.deployservice.deploy.DefaultDeployService
import com.example.deployservice.deploy.DeployService
import com.example.deployservice.deploy.DeploymentExecutor
import com.example.deployservice.deploy.DeploymentRunner
import com.example.deployservice.deploy.DeploymentStore
import com.example.deployservice.deploy.InfraCommitLock
import com.example.deployservice.gateway.ArgoCdGateway
import com.example.deployservice.gateway.ArgoCdRestGateway
import com.example.deployservice.gateway.Fabric8KubernetesGateway
import com.example.deployservice.gateway.GitHubGateway
import com.example.deployservice.gateway.GitHubRestGateway
import com.example.deployservice.gateway.KubernetesGateway
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration

/**
 * 외부 시스템 클라이언트와 배포 파이프라인 빈. 게이트웨이(GitHub·Argo CD·k8s)는 인터페이스로 두어 테스트가 가짜로 바꿔 끼운다.
 * 토큰은 config-repo/deploy-service.yml 의 {cipher} 값(config-service 가 복호화)이고 로그에 남기지 않는다.
 */
@Configuration
class DeployWiring {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun gitHubGateway(properties: DeployProperties): GitHubGateway {
        val gh = properties.github
        val client = RestClient.builder()
            .baseUrl(gh.apiUrl.trimEnd('/'))
            .requestFactory(requestFactory())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${gh.token}")
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build()
        return GitHubRestGateway(client, gh.owner)
    }

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
        commitLock: InfraCommitLock,
    ): DeploymentExecutor = DeploymentExecutor(properties, github, argo, kubernetes, store, clock, commitLock = commitLock)

    @Bean
    fun deploymentRunner(store: DeploymentStore, executor: DeploymentExecutor): DeploymentRunner = DeploymentRunner(store, executor)

    @Bean
    fun deployService(
        properties: DeployProperties,
        github: GitHubGateway,
        kubernetes: KubernetesGateway,
        store: DeploymentStore,
        runner: DeploymentRunner,
        clock: Clock,
    ): DeployService = DefaultDeployService(properties, github, kubernetes, store, runner, clock)

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
