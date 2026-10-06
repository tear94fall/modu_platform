package com.example.deployservice.gateway

import com.example.deployservice.deploy.ContainerVersion
import java.time.Instant

/** GitHub Contents API 로 읽은 파일: 블롭 sha(PUT 때 필요)와 디코딩한 내용. */
data class RepoFile(val sha: String, val content: String)

/** 커밋 하나: 전체 sha 와 GitHub 화면 주소. */
data class RepoCommit(val sha: String, val htmlUrl: String)

/** GitHub(저장소 내용·커밋·GHCR 패키지). 구현은 [GitHubRestGateway], 테스트는 가짜. */
interface GitHubGateway {
    /** [repo] 의 [branch] 에 있는 [path] 파일. */
    fun getFile(repo: String, branch: String, path: String): RepoFile

    /** [path] 를 [content] 로 바꿔 커밋한다. [sha] 는 읽었을 때의 블롭 sha(다른 커밋이 끼어들면 409). */
    fun putFile(repo: String, branch: String, path: String, content: String, sha: String, message: String): RepoCommit

    /** [branch] 의 HEAD 커밋. */
    fun branchHead(repo: String, branch: String): RepoCommit

    /** GHCR 컨테이너 패키지 [packageName](`modu-chat/point-service` 처럼 owner 뒤 경로)의 버전들(태그·생성 시각). */
    fun containerVersions(packageName: String): List<ContainerVersion>

    /** [repo] 의 커밋 [sha] 메시지 첫 줄. 못 읽으면 null. */
    fun commitMessage(repo: String, sha: String): String?
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

/** 쿠버네티스 API 서버(Deployment·ReplicaSet·Pod 읽기만). */
interface KubernetesGateway {
    fun deployment(name: String): DeploymentSnapshot?

    /** [deploymentName] 셀렉터에 잡히는 파드들. */
    fun pods(deploymentName: String): List<PodSnapshot>
}
