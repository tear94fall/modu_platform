package com.example.deployservice.deploy

import com.example.deployservice.api.ApiException
import com.example.deployservice.config.DeployProperties
import com.example.deployservice.gateway.DeploymentSnapshot
import com.example.deployservice.gateway.GitHubGateway
import com.example.deployservice.gateway.KubernetesGateway
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

// ---- 응답 모양(계약 문서 그대로) --------------------------------------------------------------------------------------------

enum class ServiceHealth { READY, PROGRESSING, DEGRADED }

data class LastDeploymentView(val id: String, val tag: String, val by: String, val byId: String?, val finishedAt: Instant?, val status: DeploymentStatus)

data class ServiceView(
    val name: String,
    val repo: String,
    val image: String,
    val gitTag: String,
    val runningTag: String,
    val readyReplicas: Int,
    val desiredReplicas: Int,
    val updatedReplicas: Int,
    val status: ServiceHealth,
    val lastDeployment: LastDeploymentView?,
)

data class ArgoCdView(val application: String, val url: String)

data class ServicesResponse(val services: List<ServiceView>, val argocd: ArgoCdView)

data class TagView(
    val tag: String,
    val sha: String,
    val createdAt: Instant,
    val commitMessage: String?,
    val commitUrl: String,
    val current: Boolean,
)

data class TagsResponse(val tags: List<TagView>)

data class DeploymentStartedResponse(
    val id: String,
    val service: String,
    val tag: String,
    val by: String,
    val byId: String? = null,
    val startedAt: Instant,
    val status: DeploymentStatus,
    val step: Step,
    val percent: Int,
) {
    companion object {
        fun of(r: DeploymentRecord) = DeploymentStartedResponse(r.id, r.service, r.tag, r.by, r.byId, r.startedAt, r.status, r.step, r.percent)
    }
}

data class DeploymentsResponse(val deployments: List<DeploymentRecord>)

/** 컨트롤러가 부르는 서비스 계층. 외부 시스템은 게이트웨이 인터페이스로 받는다. */
interface DeployService {
    fun services(): ServicesResponse
    fun tags(service: String): TagsResponse
    fun deploy(service: String, tag: String, by: String, byId: String? = null): DeploymentStartedResponse
    fun rollback(service: String, by: String, byId: String? = null): DeploymentStartedResponse
    fun deployment(id: String): DeploymentRecord
    fun deployments(service: String?, limit: Int): DeploymentsResponse
}

class DefaultDeployService(
    private val properties: DeployProperties,
    private val github: GitHubGateway,
    private val kubernetes: KubernetesGateway,
    private val store: DeploymentStore,
    private val runner: DeploymentRunner,
    private val clock: Clock = Clock.systemUTC(),
) : DeployService {

    private val log = LoggerFactory.getLogger(javaClass)
    private val commitMessages = ConcurrentHashMap<String, String>()
    private val random = SecureRandom()

    companion object {
        /** images[].name 에서 GHCR 패키지 이름을 얻을 때 떼는 접두사. */
        private const val GHCR = "ghcr.io/"
        private val ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        private const val UNPINNED_TAG = "develop"
    }

    override fun services(): ServicesResponse {
        val kustomization = readKustomization()
        val services = properties.services.map { spec ->
            val gitTag = Kustomization.currentTag(kustomization, spec.image) ?: UNPINNED_TAG
            val deployment = runCatching { kubernetes.deployment(spec.name) }
                .getOrElse { log.warn("deployment {} lookup failed: {}", spec.name, it.message); null }
            ServiceView(
                name = spec.name,
                repo = spec.repo,
                image = spec.image,
                gitTag = gitTag,
                runningTag = deployment?.imageTag ?: "",
                readyReplicas = deployment?.ready ?: 0,
                desiredReplicas = deployment?.desired ?: 0,
                updatedReplicas = deployment?.updated ?: 0,
                status = health(deployment),
                lastDeployment = store.latest(spec.name)?.let { LastDeploymentView(it.id, it.tag, it.by, it.byId, it.finishedAt, it.status) },
            )
        }
        return ServicesResponse(services, ArgoCdView(properties.argocd.application, properties.argocd.applicationUrl()))
    }

    override fun tags(service: String): TagsResponse {
        val spec = spec(service)
        val gitTag = Kustomization.currentTag(readKustomization(), spec.image) ?: UNPINNED_TAG
        val versions = runCatching { github.containerVersions(packageName(spec.image)) }
            .getOrElse { throw ApiException.upstream("github_error", "GHCR 태그 조회 실패: ${it.message}") }
        val tags = Tags.select(versions).map { t ->
            TagView(
                tag = t.tag,
                sha = t.sha,
                createdAt = t.createdAt,
                commitMessage = commitMessage(spec.repo, t.sha),
                commitUrl = "https://github.com/${properties.github.owner}/${spec.repo}/commit/${t.sha}",
                current = t.tag == gitTag,
            )
        }
        return TagsResponse(tags)
    }

    override fun deploy(service: String, tag: String, by: String, byId: String?): DeploymentStartedResponse {
        val spec = spec(service)
        if (!Tags.isDeployable(tag)) {
            throw ApiException.badRequest("invalid_tag", "태그는 develop-<sha7> 또는 master-<sha7> 모양이어야 합니다: $tag")
        }
        // 빠른 사전 확인(GHCR 조회 전에). 최종 판단은 runner.start 의 잠금 안 확인.
        if (store.hasRunning(service)) throw ApiException.conflict("deploy_in_progress", "$service 는 이미 배포 중입니다.")
        // GHCR 에 있는 태그인지(선택 검사). GHCR 조회 자체가 실패하면 막지 않고 넘어간다 — 커밋·Sync 가 실패로 드러난다.
        runCatching { github.containerVersions(packageName(spec.image)) }
            .onSuccess { versions ->
                if (versions.none { tag in it.tags }) throw ApiException.badRequest("unknown_tag", "GHCR 에 없는 태그입니다: $tag")
            }
            .onFailure { log.warn("ghcr check skipped for {}: {}", spec.image, it.message) }
        val record = DeploymentRecord(id = newId(), service = service, tag = tag, by = by, byId = byId, startedAt = clock.instant())
        return DeploymentStartedResponse.of(runner.start(record))
    }

    override fun rollback(service: String, by: String, byId: String?): DeploymentStartedResponse {
        spec(service)
        val last = store.latestSucceeded(service)
            ?: throw ApiException.conflict("no_rollback_target", "$service 의 성공한 배포 기록이 없어 되돌릴 태그를 모릅니다.")
        val previous = last.previousTag
            ?: throw ApiException.conflict("no_rollback_target", "$service 의 이전 태그를 모릅니다.")
        if (!Tags.isDeployable(previous)) {
            throw ApiException.conflict("no_rollback_target", "이전 태그 $previous 는 커밋 태그가 아니라 되돌릴 수 없습니다.")
        }
        return deploy(service, previous, by, byId)
    }

    override fun deployment(id: String): DeploymentRecord =
        store.get(id) ?: throw ApiException.notFound("deployment_not_found", "배포 기록이 없습니다: $id")

    override fun deployments(service: String?, limit: Int): DeploymentsResponse =
        DeploymentsResponse(store.list(service?.takeIf { it.isNotBlank() }, limit.coerceIn(1, properties.store.capacity.coerceAtLeast(1))))

    // ---- helpers ---------------------------------------------------------------------------------------------------------

    private fun spec(service: String): DeployProperties.ServiceSpec =
        properties.service(service) ?: throw ApiException.notFound("unknown_service", "배포 목록에 없는 서비스입니다: $service")

    private fun readKustomization(): String {
        val gh = properties.github
        return runCatching { github.getFile(gh.infraRepo, gh.infraBranch, gh.kustomizationPath).content }
            .getOrElse { throw ApiException.upstream("github_error", "kustomization.yaml 읽기 실패: ${it.message}") }
    }

    /** `ghcr.io/<owner>/<pkg>` → `<pkg>`. */
    internal fun packageName(image: String): String =
        image.removePrefix(GHCR).removePrefix("${properties.github.owner}/")

    private fun commitMessage(repo: String, sha: String): String? =
        commitMessages["$repo/$sha"] ?: runCatching { github.commitMessage(repo, sha) }
            .getOrElse { log.debug("commit message {}@{} unavailable: {}", repo, sha, it.message); null }
            ?.also { commitMessages["$repo/$sha"] = it }

    private fun health(d: DeploymentSnapshot?): ServiceHealth = when {
        d == null -> ServiceHealth.DEGRADED
        d.availableCondition == "False" || d.progressingCondition == "False" -> ServiceHealth.DEGRADED
        d.rolledOut -> ServiceHealth.READY
        else -> ServiceHealth.PROGRESSING
    }

    private fun newId(): String = "dep-${ID_TIME.format(clock.instant())}-${"%04x".format(random.nextInt(0x10000))}"
}
