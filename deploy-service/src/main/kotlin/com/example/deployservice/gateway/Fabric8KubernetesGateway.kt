package com.example.deployservice.gateway

import com.example.deployservice.api.UpstreamException
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.base.PatchContext
import io.fabric8.kubernetes.client.dsl.base.PatchType
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext

/**
 * fabric8 로 Deployment·ReplicaSet·Pod 를 읽고, Rollout(argoproj.io/v1alpha1, GenericKubernetesResource)은 읽기 + status 패치(카나리 진행/중단).
 * 파드 안에선 ServiceAccount(RBAC: apps/deployments·replicasets, pods get/list, rollouts get/list/patch, rollouts/status patch), 로컬에선 ~/.kube/config.
 */
class Fabric8KubernetesGateway(private val client: KubernetesClient, private val namespace: String) : KubernetesGateway {

    companion object {
        const val POD_TEMPLATE_HASH = "pod-template-hash"
        const val REVISION_ANNOTATION = "deployment.kubernetes.io/revision"
        /** Argo Rollouts 가 파드에 붙이는 ReplicaSet 해시 라벨(status.currentPodHash 와 같은 값). */
        const val ROLLOUT_HASH = "rollouts-pod-template-hash"
        val ROLLOUT: ResourceDefinitionContext = ResourceDefinitionContext.Builder()
            .withGroup("argoproj.io").withVersion("v1alpha1").withKind("Rollout").withPlural("rollouts").withNamespaced(true).build()

        @Suppress("UNCHECKED_CAST")
        private fun map(o: Any?): Map<String, Any?> = (o as? Map<String, Any?>) ?: emptyMap()
        private fun int(o: Any?): Int = when (o) { is Number -> o.toInt(); is String -> o.toIntOrNull() ?: 0; else -> 0 }

        /** Rollout CR → 스냅샷. [imageTag] 는 같은 이름의 Deployment 템플릿에서 읽은 것(없으면 빈 문자열). */
        fun snapshot(r: GenericKubernetesResource, imageTag: String?): RolloutSnapshot {
            val spec = map(r.additionalProperties["spec"]); val status = map(r.additionalProperties["status"])
            val steps = (map(map(spec["strategy"])["canary"])["steps"] as? List<*>).orEmpty()
            val weights = steps.mapNotNull { map(it)["setWeight"]?.let(::int) }
            val pauseConditions = (status["pauseConditions"] as? List<*>).orEmpty()
            return RolloutSnapshot(
                name = r.metadata.name,
                desired = spec["replicas"]?.let(::int) ?: 1,
                replicas = int(status["replicas"]),
                updated = int(status["updatedReplicas"]),
                ready = int(status["readyReplicas"]),
                available = int(status["availableReplicas"]),
                phase = (status["phase"] as? String) ?: "",
                message = status["message"] as? String,
                currentStepIndex = status["currentStepIndex"]?.let(::int),
                stepWeights = weights,
                stepCount = steps.size,
                paused = pauseConditions.isNotEmpty(),
                aborted = status["abort"] == true,
                currentPodHash = (status["currentPodHash"] as? String) ?: "",
                stableRS = (status["stableRS"] as? String) ?: "",
                imageTag = imageTag ?: "",
            )
        }

        fun rolloutPodSnapshot(pod: Pod, currentPodHash: String?): PodSnapshot {
            val status = pod.status
            val containers = status?.containerStatuses.orEmpty()
            val waiting = containers.firstNotNullOfOrNull { it.state?.waiting }
            return PodSnapshot(
                name = pod.metadata.name,
                phase = status?.phase ?: "",
                ready = containers.isNotEmpty() && containers.all { it.ready },
                reason = waiting?.reason ?: "",
                message = waiting?.message ?: "",
                fromNewestReplicaSet = !currentPodHash.isNullOrEmpty() && pod.metadata.labels?.get(ROLLOUT_HASH) == currentPodHash,
            )
        }

        fun snapshot(d: Deployment): DeploymentSnapshot {
            val status = d.status
            val conditions = status?.conditions.orEmpty()
            val image = d.spec?.template?.spec?.containers?.firstOrNull()?.image ?: ""
            return DeploymentSnapshot(
                name = d.metadata.name,
                generation = d.metadata.generation ?: 0,
                observedGeneration = status?.observedGeneration ?: 0,
                desired = d.spec?.replicas ?: 1,
                replicas = status?.replicas ?: 0,
                updated = status?.updatedReplicas ?: 0,
                ready = status?.readyReplicas ?: 0,
                available = status?.availableReplicas ?: 0,
                imageTag = imageTag(image),
                availableCondition = conditions.firstOrNull { it.type == "Available" }?.status,
                progressingCondition = conditions.firstOrNull { it.type == "Progressing" }?.status,
            )
        }

        /** `ghcr.io/x/y:develop-abc1234` → `develop-abc1234`; 태그가 없으면 빈 문자열(다이제스트 참조·latest 생략). */
        fun imageTag(image: String): String {
            val afterSlash = image.substringAfterLast('/').substringBefore('@')
            return if (':' in afterSlash) afterSlash.substringAfterLast(':') else ""
        }

        fun snapshot(pod: Pod, newestHash: String?): PodSnapshot {
            val status = pod.status
            val containers = status?.containerStatuses.orEmpty()
            val waiting = containers.firstNotNullOfOrNull { it.state?.waiting }
            val ready = containers.isNotEmpty() && containers.all { it.ready }
            return PodSnapshot(
                name = pod.metadata.name,
                phase = status?.phase ?: "",
                ready = ready,
                reason = waiting?.reason ?: "",
                message = waiting?.message ?: "",
                fromNewestReplicaSet = newestHash != null && pod.metadata.labels?.get(POD_TEMPLATE_HASH) == newestHash,
            )
        }

        /** Deployment 가 소유한 ReplicaSet 중 revision 이 가장 큰 것의 pod-template-hash. */
        fun newestTemplateHash(deployment: Deployment, replicaSets: List<ReplicaSet>): String? =
            replicaSets
                .filter { rs -> rs.metadata.ownerReferences.orEmpty().any { it.uid == deployment.metadata.uid } }
                .maxByOrNull { it.metadata.annotations?.get(REVISION_ANNOTATION)?.toLongOrNull() ?: -1L }
                ?.metadata?.labels?.get(POD_TEMPLATE_HASH)
    }

    override fun deployment(name: String): DeploymentSnapshot? = call("Deployment $name 조회") {
        client.apps().deployments().inNamespace(namespace).withName(name).get()?.let { snapshot(it) }
    }

    override fun pods(deploymentName: String): List<PodSnapshot> = call("파드 조회") {
        val deployment = client.apps().deployments().inNamespace(namespace).withName(deploymentName).get() ?: return@call emptyList()
        val selector = deployment.spec?.selector?.matchLabels.orEmpty()
        if (selector.isEmpty()) return@call emptyList()
        val replicaSets = client.apps().replicaSets().inNamespace(namespace).withLabels(selector).list().items
        val newest = newestTemplateHash(deployment, replicaSets)
        client.pods().inNamespace(namespace).withLabels(selector).list().items.map { snapshot(it, newest) }
    }

    private fun rollouts() = client.genericKubernetesResources(ROLLOUT).inNamespace(namespace)

    override fun rollout(name: String): RolloutSnapshot? = call("Rollout $name 조회") {
        val r = rollouts().withName(name).get() ?: return@call null
        val image = client.apps().deployments().inNamespace(namespace).withName(name).get()?.spec?.template?.spec?.containers?.firstOrNull()?.image
        snapshot(r, image?.let(::imageTag))
    }

    override fun rolloutPods(name: String): List<PodSnapshot> = call("Rollout 파드 조회") {
        val r = rollouts().withName(name).get() ?: return@call emptyList()
        val selector = map(map(r.additionalProperties["spec"])["selector"])["matchLabels"] as? Map<*, *>
        if (selector.isNullOrEmpty()) return@call emptyList()
        val hash = map(r.additionalProperties["status"])["currentPodHash"] as? String
        val labels = selector.entries.associate { it.key.toString() to it.value.toString() }
        client.pods().inNamespace(namespace).withLabels(labels).list().items.map { rolloutPodSnapshot(it, hash) }
    }

    /** status 서브리소스 머지 패치 — kubectl argo rollouts 플러그인과 같은 본문. 멱등(같은 값을 다시 보내도 변화 없음). */
    private fun patchStatus(name: String, what: String, statusJson: String) = call("Rollout $name $what") {
        rollouts().withName(name).subresource("status").patch(PatchContext.of(PatchType.JSON_MERGE), """{"status":$statusJson}""")
        Unit
    }

    override fun promote(name: String) = patchStatus(name, "진행", """{"pauseConditions":null}""")

    override fun promoteFull(name: String) = patchStatus(name, "전부 진행", """{"promoteFull":true}""")

    override fun abort(name: String) = patchStatus(name, "중단", """{"abort":true}""")

    private inline fun <T> call(what: String, block: () -> T): T = try {
        block()
    } catch (e: KubernetesClientException) {
        throw UpstreamException("kubernetes", "$what 실패 (${e.code}): ${e.message}", e)
    } catch (e: UpstreamException) {
        throw e
    } catch (e: Exception) {
        throw UpstreamException("kubernetes", "$what 실패: ${e.message}", e)
    }
}
