package com.example.deployservice.deploy.workflow

import com.example.deployservice.api.UpstreamException
import com.example.deployservice.config.DeployProperties
import com.example.deployservice.deploy.CommitView
import com.example.deployservice.deploy.DeploymentStatus
import com.example.deployservice.deploy.DeploymentStore
import com.example.deployservice.deploy.Kustomization
import com.example.deployservice.deploy.PodView
import com.example.deployservice.deploy.Progress
import com.example.deployservice.deploy.RolloutView
import com.example.deployservice.deploy.Step
import com.example.deployservice.deploy.StepStatus
import com.example.deployservice.gateway.ArgoCdGateway
import com.example.deployservice.gateway.ArgoResource
import com.example.deployservice.gateway.GitHubGateway
import com.example.deployservice.gateway.KubernetesGateway
import com.example.deployservice.gateway.PodSnapshot
import io.temporal.failure.ApplicationFailure
import io.temporal.spring.boot.ActivityImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/*
 * 액티비티 구현 네 개. 전부 Spring 빈이고 @ActivityImpl 이 deploy.temporal.task-queue 의 워커에 등록한다(spring.temporal.workers-auto-discovery).
 * 실패 변환 규칙: 사람이 고쳐야 하는 실패(설정·권한·대상 없음)는 newNonRetryableFailure, 잠깐의 경쟁(sha 충돌)은 재시도 가능 실패.
 * 네트워크 오류(UpstreamException httpStatus 없음)는 그대로 던져 워크플로의 재시도 정책(3회)에 맡긴다.
 */

/** GitHub 응답 상태 중 "그사이 누가 파일을 고쳤다"(blob sha 가 낡음). */
val SHA_CONFLICT_STATUSES = setOf(409, 422)

/** 새 ReplicaSet 파드가 이 상태면 롤아웃 실패로 본다. */
val FAILING_POD_REASONS = setOf("CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull")

/** 기존 Kustomization 의 "고정되지 않음" 표시. */
private const val UNPINNED_TAG = "develop"

@Component
@ActivityImpl(taskQueues = ["\${deploy.temporal.task-queue}"])
class CommitActivitiesImpl(private val properties: DeployProperties, private val github: GitHubGateway) : CommitActivities {

    override fun commitTag(service: String, image: String, tag: String, by: String): CommitOutcome {
        val gh = properties.github
        val file = try {
            github.getFile(gh.infraRepo, gh.infraBranch, gh.kustomizationPath)
        } catch (e: Exception) {
            throw ApplicationFailure.newNonRetryableFailureWithCause("kustomization.yaml 읽기 실패: ${e.message}", "github", e)
        }
        val previousTag = Kustomization.currentTag(file.content, image) ?: UNPINNED_TAG
        if (previousTag == tag) {
            val head = try {
                github.branchHead(gh.infraRepo, gh.infraBranch)
            } catch (e: Exception) {
                throw ApplicationFailure.newNonRetryableFailureWithCause("브랜치 HEAD 조회 실패: ${e.message}", "github", e)
            }
            return CommitOutcome(head.sha, null, null, previousTag, "이미 같은 태그")
        }
        val updated = try {
            Kustomization.replaceTag(file.content, image, tag)
        } catch (e: Exception) {
            throw ApplicationFailure.newNonRetryableFailureWithCause(e.message ?: "kustomization.yaml 수정 실패", "kustomization", e)
        }
        val message = "deploy: $service → $tag (by $by)"
        try {
            val commit = github.putFile(gh.infraRepo, gh.infraBranch, gh.kustomizationPath, updated, file.sha, message)
            return CommitOutcome(commit.sha, commit.sha, commit.htmlUrl, previousTag, "커밋 ${commit.sha.take(7)}")
        } catch (e: UpstreamException) {
            if (e.httpStatus in SHA_CONFLICT_STATUSES) {
                // 낙관적 잠금 실패 — 다시 읽어 다시 얹으면 된다. 재시도 가능으로 던져 워크플로의 재시도 정책이 처음부터 다시 부른다.
                throw ApplicationFailure.newFailureWithCause("sha 충돌: ${e.message}", "sha_conflict", e)
            }
            throw ApplicationFailure.newNonRetryableFailureWithCause("커밋 실패: ${e.message}", "github", e)
        } catch (e: Exception) {
            throw ApplicationFailure.newNonRetryableFailureWithCause("커밋 실패: ${e.message}", "github", e)
        }
    }
}

@Component
@ActivityImpl(taskQueues = ["\${deploy.temporal.task-queue}"])
class ArgoActivitiesImpl(private val properties: DeployProperties, private val argo: ArgoCdGateway) : ArgoActivities {

    private val app get() = properties.argocd.application

    override fun refreshSeesRevision(revision: String): Boolean {
        val state = argo.refresh(app)
        return state.syncRevision != null && sameRevision(state.syncRevision, revision)
    }

    override fun sync(service: String, revision: String) {
        argo.sync(app, revision, listOf(ArgoResource("apps", "Deployment", service, properties.kubernetes.namespace)))
    }

    override fun operation(revision: String, requestedAtEpochMs: Long): ArgoOp? {
        val op = argo.state(app).operation ?: return null
        val requestedAt = Instant.ofEpochMilli(requestedAtEpochMs)
        // 이전 작업의 결과(Succeeded)를 우리 것으로 착각하지 않게: revision 이 같거나 우리가 요청한 뒤(5초 여유) 시작된 작업만 본다.
        val ours = (op.revision != null && sameRevision(op.revision, revision)) ||
            (op.startedAt != null && !op.startedAt.isBefore(requestedAt.minusSeconds(5)))
        if (!ours) return null
        return ArgoOp(op.phase, op.message, op.terminal)
    }

    private fun sameRevision(a: String, b: String): Boolean = a.startsWith(b) || b.startsWith(a)
}

@Component
@ActivityImpl(taskQueues = ["\${deploy.temporal.task-queue}"])
class KubernetesActivitiesImpl(private val properties: DeployProperties, private val kubernetes: KubernetesGateway) : KubernetesActivities {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun rolloutStatus(service: String): RolloutProbe {
        val deployment = kubernetes.deployment(service)
            ?: throw ApplicationFailure.newNonRetryableFailure("Deployment $service 가 네임스페이스 ${properties.kubernetes.namespace} 에 없습니다.", "kubernetes")
        val pods = runCatching { kubernetes.pods(service) }
            .getOrElse { log.warn("pods of {} unavailable: {}", service, it.message); emptyList() }
        val view = RolloutView(
            desired = deployment.desired,
            updated = deployment.updated,
            ready = deployment.ready,
            available = deployment.available,
            pods = pods.map { PodView(it.name, it.phase, it.ready, it.reason) },
        )
        val failing = pods.firstOrNull { it.fromNewestReplicaSet && it.reason in FAILING_POD_REASONS }?.let(::podFailureMessage)
        return RolloutProbe(view, deployment.rolledOut, failing)
    }

    private fun podFailureMessage(pod: PodSnapshot): String =
        "파드 ${pod.name} ${pod.reason}" + (pod.message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
}

/**
 * 기록 쓰기. 저장소 예외는 그대로 던진다 — 로컬 액티비티 재시도(3회) 뒤에도 실패하면 워크플로가 받아 실패로 끝낸다.
 * (DB 가 내려가 fail 조차 못 쓰면 기록은 RUNNING 으로 남는다 → 다음 배포 시작 때 DeploymentStarter 가 워크플로 상태를 보고 닫는다.)
 */
@Component
@ActivityImpl(taskQueues = ["\${deploy.temporal.task-queue}"])
class RecordActivitiesImpl(private val store: DeploymentStore, private val clock: Clock) : RecordActivities {

    private fun now(): Instant = clock.instant()

    override fun startStep(id: String, step: Step, percent: Int) {
        val at = now()
        store.update(id) { r ->
            r.copy(step = step, percent = percent).withStep(step) { s -> s.copy(status = StepStatus.RUNNING, startedAt = at) }
        }
    }

    override fun message(id: String, step: Step, text: String) {
        store.update(id) { r -> r.withStep(step) { s -> s.copy(message = text) } }
    }

    override fun finishStep(id: String, step: Step, percent: Int, text: String) {
        val at = now()
        store.update(id) { r ->
            r.copy(percent = percent).withStep(step) { s -> s.copy(status = StepStatus.SUCCEEDED, message = text, finishedAt = at) }
        }
    }

    override fun previousTag(id: String, tag: String) {
        store.update(id) { it.copy(previousTag = tag) }
    }

    override fun commit(id: String, sha: String, url: String) {
        store.update(id) { it.copy(commit = CommitView(sha, url)) }
    }

    override fun rollout(id: String, view: RolloutView, percent: Int) {
        store.update(id) { it.copy(rollout = view, percent = percent) }
    }

    override fun succeed(id: String) {
        val at = now()
        store.update(id) { it.copy(status = DeploymentStatus.SUCCEEDED, step = Step.DONE, percent = Progress.DONE, finishedAt = at) }
    }

    override fun fail(id: String, step: Step, error: String) {
        val at = now()
        store.update(id) { r ->
            if (r.status != DeploymentStatus.RUNNING) return@update r
            r.copy(status = DeploymentStatus.FAILED, error = error, finishedAt = at)
                .withStep(step) { s -> s.copy(status = StepStatus.FAILED, message = error, startedAt = s.startedAt ?: at, finishedAt = at) }
        }
    }
}
