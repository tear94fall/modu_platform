package com.example.deployservice.deploy

import com.example.deployservice.api.ApiException
import com.example.deployservice.config.DeployProperties
import com.example.deployservice.gateway.ContainerRegistryGateway
import com.example.deployservice.gateway.DeploymentSnapshot
import com.example.deployservice.gateway.GitHubGateway
import com.example.deployservice.gateway.KubernetesGateway
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import com.example.deployservice.gateway.ErrorLogs
import com.example.deployservice.gateway.LogsGateway
import com.example.deployservice.gateway.RolloutSnapshot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

// ---- 응답 모양(계약 문서 그대로) --------------------------------------------------------------------------------------------

/** PAUSED = Rollout 이 카나리 단계에서 멈춰 사람을 기다린다(배포 기록이 있을 수도, 없을 수도). */
enum class ServiceHealth { READY, PROGRESSING, DEGRADED, PAUSED }

/** 단계에서 멈춘 Rollout. [deploymentId] 가 없으면 배포 탭 밖에서 템플릿이 바뀐 것 — 서비스 행의 진행/중단으로 푼다. */
data class RolloutPausedView(val stepIndex: Int, val weight: Int, val deploymentId: String?)

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
    /** 파드 2개 이상(Rollout 기준) — 카나리 배포 버튼 활성. */
    val canaryCapable: Boolean = false,
    /** 카나리 단계 가중치(예: [50] 또는 [33, 66]). 파드 1개면 빈 목록. */
    val canarySteps: List<Int> = emptyList(),
    val rolloutPaused: RolloutPausedView? = null,
)

data class ArgoCdView(val application: String, val url: String)

/** 콘솔 "Temporal 에서 보기" 의 뿌리(맥 루프백 전용 UI)와 네임스페이스. 기록별 링크는 DeploymentRecord.workflowUrl. */
data class TemporalView(val namespace: String, val url: String)

data class ServicesResponse(val services: List<ServiceView>, val argocd: ArgoCdView, val temporal: TemporalView)

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
    fun deploy(service: String, tag: String, by: String, byId: String? = null, mode: DeployMode = DeployMode.DIRECT): DeploymentStartedResponse
    fun rollback(service: String, by: String, byId: String? = null): DeploymentStartedResponse
    /** 기록 한 건. 카나리 대기 중이면 Rollout·파드를 지금 읽어 canary.live 로 덧붙인다(콘솔 2초 폴링). */
    fun deployment(id: String): DeploymentRecord
    fun deployments(service: String?, limit: Int): DeploymentsResponse
    /** 카나리 다음 단계(Signal). 기록이 RUNNING·CANARY·대기 중이 아니면 409 not_waiting. */
    fun promote(id: String, by: String)
    /** 카나리 중단(Signal). */
    fun abort(id: String, by: String)
    /** 대기 중인 카나리의 새 파드 ERROR 로그(OpenSearch). */
    fun errors(id: String, since: Instant): ErrorLogs
    /** 배포 기록 없이 멈춘 Rollout 을 직접 진행. 그 서비스에 RUNNING 기록이 있으면 409 use_deployment(기록 쪽 버튼을 쓰라). */
    fun promoteRollout(service: String, by: String)
    fun abortRollout(service: String, by: String)
}

class DefaultDeployService(
    private val properties: DeployProperties,
    private val github: GitHubGateway,
    private val registry: ContainerRegistryGateway,
    private val kubernetes: KubernetesGateway,
    private val store: DeploymentStore,
    private val starter: DeploymentStarter,
    private val clock: Clock = Clock.systemUTC(),
    private val logs: LogsGateway,
) : DeployService {

    private val log = LoggerFactory.getLogger(javaClass)
    private val temporalGateway: TemporalGateway get() = starter.temporal
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
            val rollout = runCatching { kubernetes.rollout(spec.name) }
                .getOrElse { log.warn("rollout {} lookup failed: {}", spec.name, it.message); null }
            val lastDeployment = store.latest(spec.name)?.let { LastDeploymentView(it.id, it.tag, it.by, it.byId, it.finishedAt, it.status) }
            if (rollout != null) {
                val running = store.running(spec.name).firstOrNull()
                ServiceView(
                    name = spec.name, repo = spec.repo, image = spec.image, gitTag = gitTag,
                    runningTag = rollout.imageTag, readyReplicas = rollout.ready, desiredReplicas = rollout.desired, updatedReplicas = rollout.updated,
                    status = health(rollout), lastDeployment = lastDeployment,
                    canaryCapable = rollout.desired >= 2, canarySteps = rollout.stepWeights,
                    rolloutPaused = if (rollout.pausedAtStep) RolloutPausedView(rollout.currentStepIndex ?: 0, rollout.currentWeight, running?.id) else null,
                )
            } else {
                // 전환 전 서비스(Rollout 없음): Deployment 기준 — 카나리 불가
                val deployment = runCatching { kubernetes.deployment(spec.name) }
                    .getOrElse { log.warn("deployment {} lookup failed: {}", spec.name, it.message); null }
                ServiceView(
                    name = spec.name, repo = spec.repo, image = spec.image, gitTag = gitTag,
                    runningTag = deployment?.imageTag ?: "", readyReplicas = deployment?.ready ?: 0, desiredReplicas = deployment?.desired ?: 0, updatedReplicas = deployment?.updated ?: 0,
                    status = health(deployment), lastDeployment = lastDeployment,
                )
            }
        }
        return ServicesResponse(
            services,
            ArgoCdView(properties.argocd.application, properties.argocd.applicationUrl()),
            TemporalView(properties.temporal.namespace, properties.temporal.publicUrl),
        )
    }

    override fun tags(service: String): TagsResponse {
        val spec = spec(service)
        val gitTag = Kustomization.currentTag(readKustomization(), spec.image) ?: UNPINNED_TAG
        val versions = runCatching { registry.containerVersions(packageName(spec.image)) }
            .getOrElse { throw ApiException.upstream("github_error", "GHCR 태그 조회 실패: ${it.message}") }
        val messages = CommitMessages(spec.repo)
        val tags = Tags.select(versions).map { t ->
            TagView(
                tag = t.tag,
                sha = t.sha,
                createdAt = t.createdAt,
                commitMessage = messages.of(t.sha),
                commitUrl = "https://github.com/${properties.github.owner}/${spec.repo}/commit/${t.sha}",
                current = t.tag == gitTag,
            )
        }
        return TagsResponse(tags)
    }

    override fun deploy(service: String, tag: String, by: String, byId: String?, mode: DeployMode): DeploymentStartedResponse {
        val spec = spec(service)
        if (!Tags.isDeployable(tag)) {
            throw ApiException.badRequest("invalid_tag", "태그는 develop-<sha7> 또는 master-<sha7> 모양이어야 합니다: $tag")
        }
        if (mode == DeployMode.CANARY) {
            val rollout = runCatching { kubernetes.rollout(service) }.getOrNull()
            if (rollout == null || rollout.desired < 2) throw ApiException.badRequest("canary_not_available", "파드가 1개라 카나리 배포를 할 수 없다: $service")
        }
        // "이미 배포 중" 판단은 여기서 하지 않는다 — starter.start 가 DB 유니크 키 → 워크플로 상태 순으로 보고, 워크플로가 사라진 고아 RUNNING 기록은 닫고 진행한다.
        // 여기서 hasRunning 으로 먼저 409 를 내면 그 조정이 영원히 돌지 않는다(2026-11-15 dev 에서 확인).
        // GHCR 에 있는 태그인지(선택 검사, 날짜는 읽지 않는다). GHCR 조회 자체가 실패하면 막지 않고 넘어간다 — 커밋·Sync 가 실패로 드러난다.
        runCatching { registry.tagNames(packageName(spec.image)) }
            .onSuccess { tags ->
                if (tag !in tags) throw ApiException.badRequest("unknown_tag", "GHCR 에 없는 태그입니다: $tag")
            }
            .onFailure { log.warn("ghcr check skipped for {}: {}", spec.image, it.message) }
        val record = DeploymentRecord(id = newId(), service = service, tag = tag, by = by, byId = byId, startedAt = clock.instant(), mode = mode)
        return DeploymentStartedResponse.of(starter.start(record, spec.image))
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

    override fun deployment(id: String): DeploymentRecord {
        val record = store.get(id) ?: throw ApiException.notFound("deployment_not_found", "배포 기록이 없습니다: $id")
        return TemporalLinks.decorate(properties.temporal, withLive(record))
    }

    /** 대기 중인 카나리면 Rollout·파드를 지금 읽어 canary.live 를 채운다(저장 안 함). 읽기 실패는 live 없이 돌려준다. */
    private fun withLive(record: DeploymentRecord): DeploymentRecord {
        val canary = record.canary ?: return record
        if (record.status != DeploymentStatus.RUNNING || !canary.waiting) return record
        val rollout = runCatching { kubernetes.rollout(record.service) }.getOrNull() ?: return record
        val pods = runCatching { kubernetes.rolloutPods(record.service) }.getOrDefault(emptyList())
        val live = RolloutLive(
            phase = rollout.phase, weight = rollout.currentWeight,
            pods = pods.map { PodView(it.name, it.phase, it.ready, it.reason) },
            ready = rollout.ready, desired = rollout.desired,
        )
        return record.copy(canary = canary.copy(live = live))
    }

    override fun promote(id: String, by: String) = signal(id, "promote", by)

    override fun abort(id: String, by: String) = signal(id, "abort", by)

    private fun signal(id: String, name: String, by: String) {
        val record = store.get(id) ?: throw ApiException.notFound("deployment_not_found", "배포 기록이 없습니다: $id")
        val runId = record.workflowRunId
        if (record.status != DeploymentStatus.RUNNING || record.mode != DeployMode.CANARY || record.canary?.waiting != true || runId == null) {
            throw ApiException.conflict("not_waiting", "이 배포는 지금 사람의 결정을 기다리는 카나리 단계가 아닙니다.")
        }
        try {
            temporalGateway.signal(TemporalLinks.workflowId(record.service), runId, name, by)
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException.unavailable("temporal_unavailable", "Temporal 에 신호를 보내지 못했습니다: ${e.message ?: e.javaClass.simpleName}")
        }
        log.info("deployment {} {} signalled {} by {}", id, record.service, name, by)
    }

    override fun errors(id: String, since: Instant): ErrorLogs {
        val record = store.get(id) ?: throw ApiException.notFound("deployment_not_found", "배포 기록이 없습니다: $id")
        val pods = record.canary?.canaryPods.orEmpty()
        return logs.errors(pods, since)
    }

    override fun promoteRollout(service: String, by: String) = rolloutAction(service, by, "진행") { kubernetes.promote(it) }

    override fun abortRollout(service: String, by: String) = rolloutAction(service, by, "중단") { kubernetes.abort(it) }

    private fun rolloutAction(service: String, by: String, what: String, action: (String) -> Unit) {
        spec(service)
        if (store.running(service).isNotEmpty()) {
            throw ApiException.conflict("use_deployment", "$service 는 배포 기록이 진행 중입니다 — 그 기록의 다음 단계/중단 버튼을 쓰세요.")
        }
        val rollout = kubernetes.rollout(service) ?: throw ApiException.notFound("rollout_not_found", "Rollout 이 없습니다: $service")
        if (!rollout.pausedAtStep) throw ApiException.conflict("not_paused", "$service 의 Rollout 은 단계에서 멈춰 있지 않습니다.")
        action(service)
        log.info("rollout {} {} by {} (no deployment record)", service, what, by)
    }

    override fun deployments(service: String?, limit: Int): DeploymentsResponse =
        DeploymentsResponse(
            store.list(service?.takeIf { it.isNotBlank() }, limit.coerceIn(1, properties.store.capacity.coerceAtLeast(1)))
                .map { TemporalLinks.decorate(properties.temporal, it) },
        )

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

    /**
     * 한 번의 태그 조회에서 쓰는 커밋 메시지 조회기. 다른 GitHub 호출과 같은 자격(앱 설치 토큰)으로 부른다 —
     * 설치 토큰은 앱이 설치되지 않은 공개 저장소도 읽을 수 있고 한도는 5000/시간이다.
     * 그래도 한 번 실패하면(403 rate limit·저장소가 비공개로 바뀜 등) 이 요청에서는 더 부르지 않고 메시지 없이
     * 돌려준다 — 태그 목록은 그대로 나간다(배포는 막히지 않는다). 성공한 메시지는 sha 별로 캐시한다(바뀌지 않는다).
     */
    private inner class CommitMessages(private val repo: String) {
        private var giveUp = false

        fun of(sha: String): String? {
            commitMessages["$repo/$sha"]?.let { return it }
            if (giveUp) return null
            return runCatching { github.commitMessage(repo, sha) }
                .onFailure {
                    giveUp = true
                    log.info("commit message {}@{} unavailable, 이번 조회의 나머지는 건너뜁니다: {}", repo, sha, it.message)
                }
                .getOrNull()
                ?.also { commitMessages["$repo/$sha"] = it }
        }
    }

    private fun health(r: RolloutSnapshot): ServiceHealth = when {
        r.pausedAtStep -> ServiceHealth.PAUSED
        r.phase == "Degraded" -> ServiceHealth.DEGRADED
        r.rolledOut -> ServiceHealth.READY
        else -> ServiceHealth.PROGRESSING
    }

    private fun health(d: DeploymentSnapshot?): ServiceHealth = when {
        d == null -> ServiceHealth.DEGRADED
        d.availableCondition == "False" || d.progressingCondition == "False" -> ServiceHealth.DEGRADED
        d.rolledOut -> ServiceHealth.READY
        else -> ServiceHealth.PROGRESSING
    }

    private fun newId(): String = "dep-${ID_TIME.format(clock.instant())}-${"%04x".format(random.nextInt(0x10000))}"
}
