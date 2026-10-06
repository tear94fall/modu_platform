package com.example.deployservice.deploy

import com.example.deployservice.config.DeployProperties
import com.example.deployservice.gateway.ArgoCdGateway
import com.example.deployservice.gateway.ArgoResource
import com.example.deployservice.gateway.GitHubGateway
import com.example.deployservice.gateway.KubernetesGateway
import com.example.deployservice.gateway.PodSnapshot
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 폴링 사이 쉬기. 테스트는 시계만 돌리고 실제로 자지 않는 가짜를 넣는다. */
fun interface Sleeper {
    fun sleep(duration: Duration)

    companion object {
        val REAL = Sleeper { Thread.sleep(it.toMillis()) }
    }
}

/** 단계별 제한 시간·폴링 간격. */
data class Timeouts(
    val argoSync: Duration = Duration.ofMinutes(10),
    val rollout: Duration = Duration.ofMinutes(10),
    /** 커밋 직후 Argo 가 새 revision 을 볼 때까지 refresh 를 되풀이하는 최대 시간. */
    val argoRevisionWait: Duration = Duration.ofSeconds(60),
    val poll: Duration = Duration.ofSeconds(3),
    val podPoll: Duration = Duration.ofSeconds(2),
)

/** 한 단계가 실패했다. 기록에는 그 단계가 FAILED 로 남고 배포 전체가 FAILED 가 된다. */
class DeployFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 배포 상태 기계. 한 기록을 COMMIT → SYNC → ROLLOUT 순서로 밀고 가며 [DeploymentStore] 에 진행 상황을 쓴다.
 * 외부 시스템은 전부 인터페이스(GitHub·Argo CD·k8s)로 받아 단위 테스트에서 가짜로 바꾼다.
 */
class DeploymentExecutor(
    private val properties: DeployProperties,
    private val github: GitHubGateway,
    private val argo: ArgoCdGateway,
    private val kubernetes: KubernetesGateway,
    private val store: DeploymentStore,
    private val clock: Clock = Clock.systemUTC(),
    private val sleeper: Sleeper = Sleeper.REAL,
    private val timeouts: Timeouts = Timeouts(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        val FAILING_POD_REASONS = setOf("CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull")
    }

    fun run(id: String) {
        val record = runCatching { store.get(id) }
            .getOrElse { log.error("deployment {} could not be read; not running it", id, it); return }
            ?: return
        val spec = properties.service(record.service)
            ?: return fail(id, Step.COMMIT, "알 수 없는 서비스: ${record.service}")
        try {
            val revision = commit(record, spec)
            sync(id, record.service, revision)
            rollout(id, record.service)
            write(id) {
                it.copy(status = DeploymentStatus.SUCCEEDED, step = Step.DONE, percent = Progress.DONE, finishedAt = now())
            }
            log.info("deployment {} {} → {} succeeded", id, record.service, record.tag)
        } catch (e: DeployFailure) {
            val step = currentStep(id)
            log.warn("deployment {} failed at {}: {}", id, step, e.message)
            fail(id, step, e.message ?: "실패")
        } catch (e: Exception) {
            val step = currentStep(id)
            log.error("deployment {} crashed at {}", id, step, e)
            fail(id, step, "${step} 단계에서 오류: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---- 1. COMMIT -------------------------------------------------------------------------------------------------------

    /** kustomization.yaml 의 newTag 를 바꿔 커밋하고 Sync 에 쓸 revision 을 돌려준다. */
    private fun commit(record: DeploymentRecord, spec: DeployProperties.ServiceSpec): String {
        val id = record.id
        startStep(id, Step.COMMIT, Progress.COMMIT_START)
        val gh = properties.github
        val file = runCatching { github.getFile(gh.infraRepo, gh.infraBranch, gh.kustomizationPath) }
            .getOrElse { throw DeployFailure("kustomization.yaml 읽기 실패: ${it.message}", it) }

        val previousTag = Kustomization.currentTag(file.content, spec.image) ?: "develop"
        write(id) { it.copy(previousTag = previousTag) }

        if (previousTag == record.tag) {
            val head = runCatching { github.branchHead(gh.infraRepo, gh.infraBranch) }
                .getOrElse { throw DeployFailure("브랜치 HEAD 조회 실패: ${it.message}", it) }
            finishStep(id, Step.COMMIT, Progress.COMMIT_DONE, "이미 같은 태그")
            return head.sha
        }

        val updated = runCatching { Kustomization.replaceTag(file.content, spec.image, record.tag) }
            .getOrElse { throw DeployFailure(it.message ?: "kustomization.yaml 수정 실패", it) }
        val message = "deploy: ${spec.name} → ${record.tag} (by ${record.by})"
        val commit = runCatching { github.putFile(gh.infraRepo, gh.infraBranch, gh.kustomizationPath, updated, file.sha, message) }
            .getOrElse { throw DeployFailure("커밋 실패: ${it.message}", it) }
        write(id) { it.copy(commit = CommitView(commit.sha, commit.htmlUrl)) }
        finishStep(id, Step.COMMIT, Progress.COMMIT_DONE, "커밋 ${commit.sha.take(7)}")
        return commit.sha
    }

    // ---- 2. SYNC ---------------------------------------------------------------------------------------------------------

    private fun sync(id: String, service: String, revision: String) {
        startStep(id, Step.SYNC, Progress.COMMIT_DONE)
        val app = properties.argocd.application

        // Argo 가 저장소를 다시 읽어 새 커밋을 볼 때까지(최대 argoRevisionWait) refresh 를 되풀이한다.
        val seen = waitUntil(timeouts.argoRevisionWait, timeouts.poll) {
            val state = runCatching { argo.refresh(app) }.getOrElse { throw DeployFailure("Argo CD refresh 실패: ${it.message}", it) }
            state.syncRevision != null && sameRevision(state.syncRevision, revision)
        }
        if (!seen) log.warn("argo cd did not report revision {} within {}; syncing anyway", revision.take(7), timeouts.argoRevisionWait)

        val requestedAt = now()
        val resource = ArgoResource("apps", "Deployment", service, properties.kubernetes.namespace)
        runCatching { argo.sync(app, revision, listOf(resource)) }
            .getOrElse { throw DeployFailure("Argo CD sync 요청 실패: ${it.message}", it) }
        write(id) { it.copy(percent = Progress.SYNC_REQUESTED) }
        setStepMessage(id, Step.SYNC, "동기화 중 (${revision.take(7)})")

        var phase: String? = null
        var opMessage: String? = null
        val finished = waitUntil(timeouts.argoSync, timeouts.poll) {
            val state = runCatching { argo.state(app) }.getOrElse { throw DeployFailure("Argo CD 상태 조회 실패: ${it.message}", it) }
            val op = state.operation ?: return@waitUntil false
            // 이전 작업의 결과(Succeeded)를 우리 것으로 착각하지 않게: 우리가 요청한 뒤 시작된 작업이거나 revision 이 같은 작업만 본다.
            val ours = (op.revision != null && sameRevision(op.revision, revision)) ||
                (op.startedAt != null && !op.startedAt.isBefore(requestedAt.minusSeconds(5)))
            if (!ours) return@waitUntil false
            phase = op.phase
            opMessage = op.message
            op.terminal
        }
        if (!finished) throw DeployFailure("Argo CD 동기화가 ${timeouts.argoSync.toMinutes()}분 안에 끝나지 않았습니다.")
        if (phase != "Succeeded") throw DeployFailure("Argo CD 동기화 $phase: ${opMessage ?: "(메시지 없음)"}")
        finishStep(id, Step.SYNC, Progress.SYNC_DONE, "동기화 완료")
    }

    private fun sameRevision(a: String, b: String): Boolean = a.startsWith(b) || b.startsWith(a)

    // ---- 3. ROLLOUT ------------------------------------------------------------------------------------------------------

    private fun rollout(id: String, service: String) {
        startStep(id, Step.ROLLOUT, Progress.SYNC_DONE)
        val done = waitUntil(timeouts.rollout, timeouts.podPoll) {
            val deployment = runCatching { kubernetes.deployment(service) }
                .getOrElse { throw DeployFailure("Deployment 조회 실패: ${it.message}", it) }
                ?: throw DeployFailure("Deployment $service 가 네임스페이스 ${properties.kubernetes.namespace} 에 없습니다.")
            val pods = runCatching { kubernetes.pods(service) }.getOrElse { emptyList() }
            val view = RolloutView(
                desired = deployment.desired,
                updated = deployment.updated,
                ready = deployment.ready,
                available = deployment.available,
                pods = pods.map { PodView(it.name, it.phase, it.ready, it.reason) },
            )
            write(id) { it.copy(rollout = view, percent = Progress.rollout(deployment.ready, deployment.desired)) }
            setStepMessage(id, Step.ROLLOUT, "준비 ${deployment.ready}/${deployment.desired}")

            pods.firstOrNull { it.fromNewestReplicaSet && it.reason in FAILING_POD_REASONS }?.let { bad ->
                throw DeployFailure(podFailureMessage(bad))
            }
            deployment.rolledOut
        }
        if (!done) throw DeployFailure("롤아웃이 ${timeouts.rollout.toMinutes()}분 안에 끝나지 않았습니다.")
        val ready = runCatching { store.get(id)?.rollout }.getOrNull()
        finishStep(id, Step.ROLLOUT, Progress.DONE, "준비 ${ready?.ready ?: 0}/${ready?.desired ?: 0}")
    }

    private fun podFailureMessage(pod: PodSnapshot): String =
        "파드 ${pod.name} ${pod.reason}" + (pod.message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")

    // ---- 기록 갱신 ---------------------------------------------------------------------------------------------------------
    // 저장소(DB)가 실패해도 배포 스레드가 조용히 죽지 않게: 진행 중 쓰기 실패는 DeployFailure 로 바꿔 기록을 FAILED 로 닫고,
    // FAILED 를 쓰는 것마저 실패하면 로그에 남긴다(DB 가 내려간 상황 — 재시작 뒤 StaleDeploymentRecovery 가 RUNNING 을 닫는다).

    /** 진행 상황 한 줄 UPDATE. 저장소 오류는 [DeployFailure] 로 — run() 의 catch 가 FAILED 로 닫는다. */
    private fun write(id: String, update: (DeploymentRecord) -> DeploymentRecord): DeploymentRecord? =
        try {
            store.update(id, update)
        } catch (e: Exception) {
            throw DeployFailure("배포 기록 저장 실패(DB): ${e.message ?: e.javaClass.simpleName}", e)
        }

    private fun currentStep(id: String): Step = runCatching { store.get(id)?.step }.getOrNull() ?: Step.COMMIT

    private fun startStep(id: String, step: Step, percent: Int) {
        val at = now()
        write(id) {
            it.copy(step = step, percent = percent).withStep(step) { s -> s.copy(status = StepStatus.RUNNING, startedAt = at) }
        }
    }

    private fun setStepMessage(id: String, step: Step, message: String) {
        write(id) { it.withStep(step) { s -> s.copy(message = message) } }
    }

    private fun finishStep(id: String, step: Step, percent: Int, message: String) {
        val at = now()
        write(id) {
            it.copy(percent = percent).withStep(step) { s -> s.copy(status = StepStatus.SUCCEEDED, message = message, finishedAt = at) }
        }
    }

    private fun fail(id: String, step: Step, error: String) {
        val at = now()
        try {
            store.update(id) {
                it.copy(status = DeploymentStatus.FAILED, error = error, finishedAt = at)
                    .withStep(step) { s -> s.copy(status = StepStatus.FAILED, message = error, startedAt = s.startedAt ?: at, finishedAt = at) }
            }
        } catch (e: Exception) {
            log.error("deployment {} could not be marked FAILED at {} ({}): {}", id, step, error, e.message, e)
        }
    }

    /** [check] 가 true 를 돌려줄 때까지 [interval] 마다 되풀이한다. [timeout] 을 넘기면 false. */
    private fun waitUntil(timeout: Duration, interval: Duration, check: () -> Boolean): Boolean {
        val deadline = now().plus(timeout)
        while (true) {
            if (check()) return true
            if (!now().isBefore(deadline)) return false
            sleeper.sleep(interval)
        }
    }

    private fun now(): Instant = clock.instant()
}
