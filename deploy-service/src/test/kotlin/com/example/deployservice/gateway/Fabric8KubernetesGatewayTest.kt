package com.example.deployservice.gateway

import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
}
