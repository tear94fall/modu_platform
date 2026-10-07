package com.example.deployservice

import com.example.deployservice.config.DeployProperties
import com.example.deployservice.gateway.ArgoCdGateway
import com.example.deployservice.gateway.GitHubGateway
import com.example.deployservice.gateway.KubernetesGateway
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.HealthEndpointGroups
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * 전체 컨텍스트: 설정 바인딩(deploy.*), probe, API 문서, 토큰 필터. 외부 게이트웨이는 mock(네트워크·클러스터 없음), 이력 DB 는 H2(master·replica 같은 DB).
 * fabric8 KubernetesClient 도 mock 이라 개발자의 ~/.kube/config 를 읽지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeployServiceApplicationTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val groups: HealthEndpointGroups,
    @Autowired private val healthEndpoint: HealthEndpoint,
    @Autowired private val properties: DeployProperties,
) {

    @MockitoBean
    lateinit var github: GitHubGateway

    @MockitoBean
    lateinit var argo: ArgoCdGateway

    @MockitoBean
    lateinit var kubernetes: KubernetesGateway

    /** 개발자 ~/.kube/config(EC 키 등)에 테스트가 흔들리지 않게 클라이언트 자체도 mock. */
    @MockitoBean
    lateinit var kubernetesClient: KubernetesClient

    @Test
    fun `binds deploy properties from config`() {
        assertEquals("tear94fall", properties.github.owner)
        assertEquals("k8s/overlays/dev/kustomization.yaml", properties.github.kustomizationPath)
        assertEquals("modu-dev", properties.argocd.application)
        assertEquals("http://localhost:2/applications/modu-dev", properties.argocd.applicationUrl())
        assertEquals("modu", properties.kubernetes.namespace)
        assertEquals(listOf("point-service", "gateway-service"), properties.services.map { it.name })
        assertEquals("ghcr.io/tear94fall/modu-chat/point-service", properties.service("point-service")?.image)
        assertFalse(properties.github.toString().contains("test-github-token"))
        assertFalse(properties.argocd.toString().contains("test-argocd-token"))
    }

    @Test
    fun `liveness and readiness are UP without the internal token`() {
        mvc.get("/actuator/health/liveness").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
        mvc.get("/actuator/health/readiness").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
        val readiness = groups.get("readiness")!!
        assertTrue(readiness.isMember("readinessState"))
        assertTrue(readiness.isMember("masterDb"), "readiness 는 이력 DB master 상태를 포함한다")
        assertFalse(readiness.isMember("db"), "자동 db(replica 포함)는 readiness 에 넣지 않는다 — 레플리카가 늦어도 서비스가 빠지면 안 된다")
        assertEquals(Status.UP, healthEndpoint.healthForPath("readiness", "masterDb")!!.status)
        assertNull(healthEndpoint.healthForPath("readiness", "db"))
        assertTrue(groups.get("liveness")!!.isMember("livenessState"))
    }

    @Test
    fun `api docs are open and describe the deploy endpoints`() {
        mvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.info.title") { value("deploy-service") }
            jsonPath("$.paths['/api-system/deploy/services']") { exists() }
            jsonPath("$.paths['/api-system/deploy/services/{name}/rollback']") { exists() }
            jsonPath("$.paths['/actuator/health']") { doesNotExist() }
        }
    }

    @Test
    fun `system api needs the internal token in the full context too`() {
        mvc.get("/api-system/deploy/deployments").andExpect { status { isUnauthorized() } }
        mvc.get("/api-system/deploy/deployments") { header("X-Internal-Token", "test-token") }.andExpect {
            status { isOk() }
            jsonPath("$.deployments") { isArray() }
        }
    }
}
