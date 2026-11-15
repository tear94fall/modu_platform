package com.example.deployservice.gateway

import com.example.deployservice.deploy.ContainerVersion
import java.time.Instant

/** GitHub Contents API 로 읽은 파일: 블롭 sha(PUT 때 필요)와 디코딩한 내용. */
data class RepoFile(val sha: String, val content: String)

/** 커밋 하나: 전체 sha 와 GitHub 화면 주소. */
data class RepoCommit(val sha: String, val htmlUrl: String)

/** GitHub(저장소 내용·커밋). 구현은 [GitHubRestGateway], 테스트는 가짜. GHCR 태그는 [ContainerRegistryGateway] 가 본다. */
interface GitHubGateway {
    /** [repo] 의 [branch] 에 있는 [path] 파일. */
    fun getFile(repo: String, branch: String, path: String): RepoFile

    /** [path] 를 [content] 로 바꿔 커밋한다. [sha] 는 읽었을 때의 블롭 sha(다른 커밋이 끼어들면 409). */
    fun putFile(repo: String, branch: String, path: String, content: String, sha: String, message: String): RepoCommit

    /** [branch] 의 HEAD 커밋. */
    fun branchHead(repo: String, branch: String): RepoCommit

    /**
     * [repo] 의 커밋 [sha] 메시지 첫 줄. 못 읽으면 null.
     *
     * 앱은 modu_infra 에만 설치되지만 **설치 토큰으로 공개 저장소를 읽을 수 있다**(확인: 설치 토큰으로
     * `GET /repos/tear94fall/modu_chat/contents/README.md` → 200, `GET /installation/repositories` 에는 modu_infra 만).
     * 그래서 다른 GitHub 호출과 같은 자격을 쓴다 — 익명 60/시간 대신 5000/시간이고 특별한 경로가 없다.
     */
    fun commitMessage(repo: String, sha: String): String?
}

/**
 * 컨테이너 레지스트리(GHCR)의 태그. 구현은 [GhcrRegistryGateway](레지스트리 `/v2/...` API, 익명),
 * 테스트는 가짜. [packageName] 은 owner 뒤 경로(`modu-chat/point-service`)다.
 *
 * GitHub Packages REST API 를 쓰지 않는 이유: 공개 패키지라도 인증이 필요하고 fine-grained 토큰·GitHub App 은 그 API 를 쓸 수 없다.
 */
interface ContainerRegistryGateway {
    /** 배포 가능한 태그와 각 태그의 이미지 생성 시각(못 읽은 태그는 [Instant.EPOCH]). */
    fun containerVersions(packageName: String): List<ContainerVersion>

    /** 태그 이름만 — 날짜를 읽지 않아 싸다(배포 요청 때 "GHCR 에 있는 태그인가" 확인용). */
    fun tagNames(packageName: String): List<String>
}

/** Argo CD Application 의 현재 상태 중 배포가 보는 것. */
data class ArgoApplicationState(
    /** status.sync.revision — Argo 가 저장소에서 마지막으로 본 커밋. */
    val syncRevision: String?,
    /** status.operationState — 마지막(또는 진행 중인) 작업. 없으면 null. */
    val operation: ArgoOperationState?,
)

data class ArgoOperationState(val phase: String, val message: String?, val startedAt: Instant?, val revision: String?) {
    val terminal: Boolean get() = phase == "Succeeded" || phase == "Failed" || phase == "Error"
}

/** Sync 대상 리소스 하나(`apps/Deployment <name>` 만 동기화한다). */
data class ArgoResource(val group: String, val kind: String, val name: String, val namespace: String)

/** Argo CD REST API. */
interface ArgoCdGateway {
    /** `GET /api/v1/applications/{app}?refresh=normal` — 저장소를 다시 읽게 한 뒤의 상태. */
    fun refresh(application: String): ArgoApplicationState

    /** `GET /api/v1/applications/{app}` — 현재 상태. */
    fun state(application: String): ArgoApplicationState

    /** `POST /api/v1/applications/{app}/sync` — [revision] 으로 [resources] 만 동기화(prune 없음). */
    fun sync(application: String, revision: String, resources: List<ArgoResource>)
}

/** Deployment 의 롤아웃 상태 스냅샷. 카운트는 status 의 값(없으면 0), [desired] 는 spec.replicas. */
data class DeploymentSnapshot(
    val name: String,
    val generation: Long,
    val observedGeneration: Long,
    val desired: Int,
    val replicas: Int,
    val updated: Int,
    val ready: Int,
    val available: Int,
    /** 파드 템플릿 컨테이너 이미지의 태그(`:` 뒤). */
    val imageTag: String,
    /** conditions[type=Available].status */
    val availableCondition: String?,
    /** conditions[type=Progressing].status */
    val progressingCondition: String?,
) {
    /** 새 템플릿이 반영됐고 복제본이 모두 갱신·준비·가용인가. */
    val rolledOut: Boolean
        get() = observedGeneration >= generation && updated == desired && replicas == desired && ready == desired && available == desired
}

/** 파드 하나. [fromNewestReplicaSet] 는 가장 최근 ReplicaSet(새 템플릿)의 파드인지. */
data class PodSnapshot(
    val name: String,
    val phase: String,
    val ready: Boolean,
    /** 컨테이너 waiting reason(CrashLoopBackOff 등) 또는 빈 문자열. */
    val reason: String,
    val message: String,
    val fromNewestReplicaSet: Boolean,
)

/**
 * Argo Rollouts 의 Rollout 한 개(카나리 배포, 2026-11-15). 파드 템플릿은 같은 이름의 Deployment(workloadRef)에 있어 [imageTag] 는 거기서 읽는다.
 * [stepWeights] 는 strategy.canary.steps 의 setWeight 들(단계 수 = [totalSteps]). [currentStepIndex] 는 status.currentStepIndex(0 = 첫 단계 전, steps.size = 전부 끝).
 */
data class RolloutSnapshot(
    val name: String,
    val desired: Int,
    val replicas: Int,
    val updated: Int,
    val ready: Int,
    val available: Int,
    /** Healthy | Progressing | Paused | Degraded (비어 있을 수 있다 — 컨트롤러가 아직 안 봤을 때). */
    val phase: String,
    val message: String?,
    val currentStepIndex: Int?,
    val stepWeights: List<Int>,
    /** strategy.canary.steps 의 전체 길이(setWeight + pause). currentStepIndex ≥ stepCount 면 단계를 전부 지났다. */
    val stepCount: Int,
    /** 사람이 풀어야 하는 일시정지(status.pauseConditions 가 비어 있지 않음). */
    val paused: Boolean,
    val aborted: Boolean,
    val currentPodHash: String,
    val stableRS: String,
    val imageTag: String,
) {
    val totalSteps: Int get() = stepWeights.size

    /** 지금 단계까지 적용된 가중치. 단계가 없거나 전부 지났으면 100, 첫 단계 전이면 0. */
    val currentWeight: Int
        get() {
            if (stepWeights.isEmpty()) return 100
            val idx = currentStepIndex ?: return 0
            if (idx >= stepCount) return 100
            // steps = [setWeight, pause, setWeight, pause, …]: index i 에 멈춰 있으면 지난 setWeight 개수 = ceil(i/2)
            val passed = (idx + 1) / 2
            return if (passed <= 0) 0 else stepWeights[minOf(passed, stepWeights.size) - 1]
        }

    val rolledOut: Boolean
        get() = phase == "Healthy" && updated == desired && ready == desired && available == desired && stableRS == currentPodHash && desired > 0

    val pausedAtStep: Boolean get() = paused && currentStepIndex != null
}

/** 쿠버네티스 API 서버. Deployment·ReplicaSet·Pod 는 읽기만, Rollout 은 읽기 + status 패치(진행/중단). */
interface KubernetesGateway {
    fun deployment(name: String): DeploymentSnapshot?

    /** [deploymentName] 셀렉터에 잡히는 파드들. */
    fun pods(deploymentName: String): List<PodSnapshot>

    /** 같은 이름의 Rollout. 없으면 null(전환 전 서비스). */
    fun rollout(name: String): RolloutSnapshot?

    /** Rollout 의 파드들 — [PodSnapshot.fromNewestReplicaSet] 는 `rollouts-pod-template-hash == status.currentPodHash`. */
    fun rolloutPods(name: String): List<PodSnapshot>

    /** 일시정지 해제(다음 단계) — status 머지 패치 `{"pauseConditions": null}`. */
    fun promote(name: String)

    /** 남은 단계 전부 건너뛰고 끝까지 — `{"promoteFull": true}`. */
    fun promoteFull(name: String)

    /** 중단 — `{"abort": true}`. stable ReplicaSet 으로 돌아간다. */
    fun abort(name: String)
}
