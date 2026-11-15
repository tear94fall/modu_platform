package com.example.deployservice.deploy

import com.example.deployservice.config.DeployProperties
import com.example.deployservice.deploy.DeployProps.props
import com.example.deployservice.gateway.ArgoApplicationState
import com.example.deployservice.gateway.ContainerRegistryGateway
import com.example.deployservice.gateway.ArgoCdGateway
import com.example.deployservice.gateway.ArgoOperationState
import com.example.deployservice.gateway.ArgoResource
import com.example.deployservice.gateway.DeploymentSnapshot
import com.example.deployservice.gateway.GitHubGateway
import com.example.deployservice.gateway.KubernetesGateway
import com.example.deployservice.gateway.PodSnapshot
import com.example.deployservice.gateway.RepoCommit
import com.example.deployservice.gateway.RepoFile
import com.example.deployservice.gateway.RolloutSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque

/** 테스트 공통 설정·가짜 게이트웨이(워크플로·액티비티·서비스 테스트가 함께 쓴다). */
object DeployProps {
    const val IMAGE = "ghcr.io/tear94fall/modu-chat/point-service"
    val props = DeployProperties(
        github = DeployProperties.GitHub(owner = "tear94fall", infraRepo = "modu_infra", infraBranch = "main", kustomizationPath = "k8s/overlays/dev/kustomization.yaml", token = "t"),
        argocd = DeployProperties.ArgoCd(url = "http://argo", application = "modu-dev", token = "t"),
        kubernetes = DeployProperties.Kubernetes("modu"),
        services = listOf(DeployProperties.ServiceSpec("point-service", "modu_chat", IMAGE)),
    )
}

/** 잠 대신 시계를 돌린다. 제한 시간 검사가 결정적으로 돈다. */
class MutableClock(private var now: Instant = Instant.parse("2026-10-06T12:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId) = this
    fun advance(d: Duration) { now = now.plus(d) }
}

class FakeGitHub(var content: String) : GitHubGateway {
    val puts = mutableListOf<Triple<String, String, String>>() // content, sha, message
    var blobSha = "blob-1"
    var failPut: String? = null
    /** 다음 PUT 몇 번을 sha 충돌(이 상태 코드)로 거절한다 — 그사이 누가 파일을 고친 것처럼(sha 도 바뀐다). */
    var conflictsLeft = 0
    var conflictStatus = 409
    var reads = 0

    /** 호출 순서("get"/"put"). 재시도마다 PUT 앞에 새 읽기가 있는지 보려고 적는다. */
    val calls = mutableListOf<String>()

    override fun getFile(repo: String, branch: String, path: String): RepoFile {
        reads++
        calls += "get"
        return RepoFile(blobSha, content)
    }
    override fun putFile(repo: String, branch: String, path: String, content: String, sha: String, message: String): RepoCommit {
        calls += "put"
        failPut?.let { throw IllegalStateException(it) }
        if (conflictsLeft > 0) {
            conflictsLeft--
            blobSha = "$blobSha-moved"
            throw com.example.deployservice.api.UpstreamException("github", "GitHub 파일 커밋 실패 ($conflictStatus): sha does not match", null, conflictStatus)
        }
        puts += Triple(content, sha, message)
        this.content = content
        blobSha = "blob-${puts.size + 1}"
        return RepoCommit("c0ffee1234567890", "https://github.com/tear94fall/modu_infra/commit/c0ffee1234567890")
    }
    override fun branchHead(repo: String, branch: String) = RepoCommit("head000000000000", "https://github.com/tear94fall/modu_infra/commit/head000000000000")
    /** "<repo>/<sha>" → 메시지. 없으면 null. */
    val commitMessages = mutableMapOf<String, String>()
    var commitFailure: Exception? = null
    var commitCalls = 0

    override fun commitMessage(repo: String, sha: String): String? {
        commitCalls++
        commitFailure?.let { throw it }
        return commitMessages["$repo/$sha"]
    }
}

/** GHCR 레지스트리 가짜. [versions] 를 그대로 돌려주고, [fail] 이 있으면 던진다. */
class FakeRegistry(var versions: List<ContainerVersion> = emptyList()) : ContainerRegistryGateway {
    var fail: Exception? = null
    var versionCalls = 0
    var tagNameCalls = 0

    override fun containerVersions(packageName: String): List<ContainerVersion> {
        versionCalls++
        fail?.let { throw it }
        return versions
    }

    override fun tagNames(packageName: String): List<String> {
        tagNameCalls++
        fail?.let { throw it }
        return versions.flatMap { it.tags }
    }
}

class FakeArgo : ArgoCdGateway {
    /** refresh 가 이 횟수만큼 불린 뒤에야 새 revision 을 본다. */
    var refreshesUntilSeen = 0
    var refreshes = 0
    var seenRevision: String? = "old"
    val syncs = mutableListOf<Triple<String, String, List<ArgoResource>>>()
    /** sync 뒤 state() 가 차례로 돌려줄 작업 상태. 비면 마지막 것을 되풀이. */
    val phases = ArrayDeque<ArgoOperationState>()
    var before: ArgoOperationState? = ArgoOperationState("Succeeded", "previous op", Instant.parse("2026-10-06T11:00:00Z"), "older")
    private var last: ArgoOperationState? = null

    override fun refresh(application: String): ArgoApplicationState {
        refreshes++
        if (refreshes > refreshesUntilSeen) seenRevision = pendingRevision ?: seenRevision
        return ArgoApplicationState(seenRevision, before)
    }
    var pendingRevision: String? = null

    override fun state(application: String): ArgoApplicationState {
        if (syncs.isEmpty()) return ArgoApplicationState(seenRevision, before)
        val next = phases.pollFirst() ?: last ?: before
        last = next
        return ArgoApplicationState(seenRevision, next)
    }

    override fun sync(application: String, revision: String, resources: List<ArgoResource>) {
        syncs += Triple(application, revision, resources)
    }
}

class FakeKubernetes : KubernetesGateway {
    val snapshots = ArrayDeque<DeploymentSnapshot>()
    var pods: List<PodSnapshot> = emptyList()
    private var last: DeploymentSnapshot? = null

    /** Rollout 스냅샷 큐(비면 마지막 것을 되풀이). promote/promoteFull/abort 호출은 이름을 기록한다. */
    val rollouts = ArrayDeque<RolloutSnapshot>()
    var rolloutPods: List<PodSnapshot> = emptyList()
    val promotes = mutableListOf<String>()
    val fullPromotes = mutableListOf<String>()
    val aborts = mutableListOf<String>()
    val retries = mutableListOf<String>()
    private var lastRollout: RolloutSnapshot? = null

    override fun deployment(name: String): DeploymentSnapshot? {
        val next = snapshots.pollFirst() ?: last
        last = next
        return next
    }
    override fun pods(deploymentName: String) = pods

    override fun rollout(name: String): RolloutSnapshot? {
        val next = rollouts.pollFirst() ?: lastRollout
        lastRollout = next
        return next
    }
    override fun rolloutPods(name: String) = rolloutPods
    override fun promote(name: String) { promotes += name }
    override fun promoteFull(name: String) { fullPromotes += name }
    override fun abort(name: String) { aborts += name }
    override fun retry(name: String) { retries += name }
}

