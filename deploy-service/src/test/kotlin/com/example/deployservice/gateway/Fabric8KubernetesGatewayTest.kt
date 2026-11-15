package com.example.deployservice.gateway

import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** fabric8 모델 → 스냅샷 변환(클러스터 없이 모델 빌더로). */
class Fabric8KubernetesGatewayTest {

    @Test
    fun `deployment snapshot reads generation, counts, image tag and conditions`() {
        val d = DeploymentBuilder()
            .withNewMetadata().withName("point-service").withGeneration(4L).withUid("dep-uid").endMetadata()
            .withNewSpec().withReplicas(2)
            .withNewTemplate().withNewSpec().addNewContainer().withName("point-service").withImage("ghcr.io/tear94fall/modu-chat/point-service:develop-5708871").endContainer().endSpec().endTemplate()
            .endSpec()
            .withNewStatus().withObservedGeneration(4L).withReplicas(2).withUpdatedReplicas(2).withReadyReplicas(1).withAvailableReplicas(1)
            .addNewCondition().withType("Available").withStatus("False").endCondition()
            .addNewCondition().withType("Progressing").withStatus("True").endCondition()
            .endStatus()
            .build()

        val s = Fabric8KubernetesGateway.snapshot(d)

        assertEquals(DeploymentSnapshot("point-service", 4, 4, 2, 2, 2, 1, 1, "develop-5708871", "False", "True"), s)
        assertFalse(s.rolledOut)
        assertTrue(s.copy(ready = 2, available = 2).rolledOut)
        assertFalse(s.copy(ready = 2, available = 2, observedGeneration = 3).rolledOut)
    }

    @Test
    fun `image tag parsing`() {
        assertEquals("develop-5708871", Fabric8KubernetesGateway.imageTag("ghcr.io/tear94fall/modu-chat/point-service:develop-5708871"))
        assertEquals("develop", Fabric8KubernetesGateway.imageTag("localhost:5000/x/y:develop"))
        assertEquals("", Fabric8KubernetesGateway.imageTag("ghcr.io/x/y"))
        assertEquals("", Fabric8KubernetesGateway.imageTag("ghcr.io/x/y@sha256:abc"))
        assertEquals("v1", Fabric8KubernetesGateway.imageTag("ghcr.io/x/y:v1@sha256:abc"))
    }

    @Test
    fun `pods of the newest replica set are marked and waiting reasons surface`() {
        val deployment = DeploymentBuilder().withNewMetadata().withName("point-service").withUid("dep-uid").endMetadata().build()
        fun rs(name: String, revision: String, hash: String, owner: String = "dep-uid") = ReplicaSetBuilder()
            .withNewMetadata().withName(name).addToAnnotations("deployment.kubernetes.io/revision", revision).addToLabels("pod-template-hash", hash)
            .addNewOwnerReference().withUid(owner).withKind("Deployment").endOwnerReference().endMetadata().build()
        val newest = Fabric8KubernetesGateway.newestTemplateHash(deployment, listOf(rs("rs-old", "3", "old"), rs("rs-new", "10", "new"), rs("rs-other", "99", "other", owner = "someone-else")))
        assertEquals("new", newest)

        val crashing = PodBuilder()
            .withNewMetadata().withName("point-service-new-1").addToLabels("pod-template-hash", "new").endMetadata()
            .withNewStatus().withPhase("Running")
            .withContainerStatuses(
                ContainerStatusBuilder().withName("point-service").withReady(false)
                    .withState(ContainerStateBuilder().withNewWaiting().withReason("CrashLoopBackOff").withMessage("back-off 5m0s").endWaiting().build()).build(),
            )
            .endStatus().build()
        val healthy = PodBuilder()
            .withNewMetadata().withName("point-service-old-1").addToLabels("pod-template-hash", "old").endMetadata()
            .withNewStatus().withPhase("Running")
            .withContainerStatuses(ContainerStatusBuilder().withName("point-service").withReady(true).withState(ContainerStateBuilder().withNewRunning().endRunning().build()).build())
            .endStatus().build()

        assertEquals(PodSnapshot("point-service-new-1", "Running", false, "CrashLoopBackOff", "back-off 5m0s", true), Fabric8KubernetesGateway.snapshot(crashing, newest))
        assertEquals(PodSnapshot("point-service-old-1", "Running", true, "", "", false), Fabric8KubernetesGateway.snapshot(healthy, newest))
    }

    // ---- Rollout(Argo Rollouts CR) — GenericKubernetesResource 의 Map 에서 읽는다 ----------------------------------------------------

    private fun rollout(replicas: Int, steps: List<Map<String, Any>>, status: Map<String, Any?>): GenericKubernetesResource =
        GenericKubernetesResource().apply {
            apiVersion = "argoproj.io/v1alpha1"; kind = "Rollout"
            metadata = ObjectMetaBuilder().withName("point-service").withGeneration(3L).build()
            additionalProperties = mutableMapOf(
                "spec" to mapOf("replicas" to replicas, "strategy" to mapOf("canary" to mapOf("steps" to steps))),
                "status" to status,
            )
        }

    @Test
    fun `rollout snapshot reads phase, step index, weight, pause, abort and hashes`() {
        val r = rollout(
            2, listOf(mapOf("setWeight" to 50), mapOf("pause" to emptyMap<String, Any>())),
            mapOf("phase" to "Paused", "message" to "CanaryPauseStep", "currentStepIndex" to 1, "pauseConditions" to listOf(mapOf("reason" to "CanaryPauseStep")),
                "currentPodHash" to "abc", "stableRS" to "xyz", "replicas" to 2, "updatedReplicas" to 1, "readyReplicas" to 2, "availableReplicas" to 2, "observedGeneration" to "3"),
        )

        val s = Fabric8KubernetesGateway.snapshot(r, "develop-5708871")

        assertEquals(2, s.desired); assertEquals(1, s.updated); assertEquals(2, s.ready); assertEquals(2, s.available)
        assertEquals("Paused", s.phase)
        assertEquals(1, s.currentStepIndex); assertEquals(1, s.totalSteps); assertEquals(listOf(50), s.stepWeights)
        assertEquals(50, s.currentWeight)
        assertTrue(s.paused); assertFalse(s.aborted); assertTrue(s.pausedAtStep); assertFalse(s.rolledOut)
        assertEquals("abc", s.currentPodHash); assertEquals("xyz", s.stableRS); assertEquals("develop-5708871", s.imageTag)
    }

    @Test
    fun `rollout without steps is rolled out when healthy and hashes match`() {
        val r = rollout(1, emptyList(), mapOf("phase" to "Healthy", "currentPodHash" to "abc", "stableRS" to "abc", "replicas" to 1, "updatedReplicas" to 1, "readyReplicas" to 1, "availableReplicas" to 1))

        val s = Fabric8KubernetesGateway.snapshot(r, "develop-5708871")

        assertTrue(s.rolledOut); assertFalse(s.pausedAtStep); assertEquals(0, s.totalSteps); assertEquals(100, s.currentWeight); assertNull(s.currentStepIndex)
    }

    @Test
    fun `rollout past the last step reports weight 100 and aborted flag`() {
        val r = rollout(2, listOf(mapOf("setWeight" to 50), mapOf("pause" to emptyMap<String, Any>())),
            mapOf("phase" to "Degraded", "message" to "RolloutAborted: ...", "currentStepIndex" to 2, "abort" to true, "currentPodHash" to "new", "stableRS" to "old", "replicas" to 2, "updatedReplicas" to 0, "readyReplicas" to 2, "availableReplicas" to 2))

        val s = Fabric8KubernetesGateway.snapshot(r, "develop-9378b00")

        assertTrue(s.aborted); assertFalse(s.paused); assertEquals(100, s.currentWeight); assertFalse(s.rolledOut)
    }

    @Test
    fun `rollout pods are marked by rollouts-pod-template-hash`() {
        val newPod = PodBuilder().withNewMetadata().withName("point-service-new").addToLabels("rollouts-pod-template-hash", "abc").endMetadata()
            .withNewStatus().withPhase("Running").addNewContainerStatus().withReady(true).endContainerStatus().endStatus().build()
        val oldPod = PodBuilder().withNewMetadata().withName("point-service-old").addToLabels("rollouts-pod-template-hash", "xyz").endMetadata()
            .withNewStatus().withPhase("Running").addNewContainerStatus().withReady(true).endContainerStatus().endStatus().build()

        val snaps = listOf(newPod, oldPod).map { Fabric8KubernetesGateway.rolloutPodSnapshot(it, "abc") }

        assertTrue(snaps[0].fromNewestReplicaSet); assertFalse(snaps[1].fromNewestReplicaSet)
    }
}
