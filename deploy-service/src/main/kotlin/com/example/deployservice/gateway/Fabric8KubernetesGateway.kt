package com.example.deployservice.gateway

import com.example.deployservice.api.UpstreamException
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException

/**
 * fabric8 로 Deployment·ReplicaSet·Pod 를 읽는다(쓰기 없음). 파드 안에선 ServiceAccount(RBAC: apps/deployments·replicasets, pods get/list),
 * 로컬에선 ~/.kube/config 의 현재 컨텍스트로 붙는다.
 */
class Fabric8KubernetesGateway(private val client: KubernetesClient, private val namespace: String) : KubernetesGateway {

    companion object {
        const val POD_TEMPLATE_HASH = "pod-template-hash"
        const val REVISION_ANNOTATION = "deployment.kubernetes.io/revision"

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
