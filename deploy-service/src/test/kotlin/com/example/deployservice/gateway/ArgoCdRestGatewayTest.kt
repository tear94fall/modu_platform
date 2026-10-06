package com.example.deployservice.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Instant

class ArgoCdRestGatewayTest {

    private val builder: RestClient.Builder = RestClient.builder().baseUrl("http://argocd-server.argocd.svc").defaultHeader("Authorization", "Bearer argo-token")
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()
    private val gateway = ArgoCdRestGateway(builder.build())

    private val app = """{"metadata":{"name":"modu-dev"},"status":{
        "sync":{"status":"OutOfSync","revision":"c0ffee1234567890"},
        "operationState":{"phase":"Running","message":"waiting","startedAt":"2026-10-06T12:45:00Z","operation":{"sync":{"revision":"c0ffee1234567890"}}}
    }}"""

    @Test
    fun `refresh and state parse revision and operation`() {
        server.expect(requestTo("http://argocd-server.argocd.svc/api/v1/applications/modu-dev?refresh=normal"))
            .andExpect(header("Authorization", "Bearer argo-token"))
            .andRespond(withSuccess(app, MediaType.APPLICATION_JSON))
        server.expect(requestTo("http://argocd-server.argocd.svc/api/v1/applications/modu-dev"))
            .andRespond(withSuccess("""{"status":{"sync":{"status":"Synced"}}}""", MediaType.APPLICATION_JSON))

        val refreshed = gateway.refresh("modu-dev")
        assertEquals("c0ffee1234567890", refreshed.syncRevision)
        assertEquals(ArgoOperationState("Running", "waiting", Instant.parse("2026-10-06T12:45:00Z"), "c0ffee1234567890"), refreshed.operation)
        assertTrue(refreshed.operation?.terminal == false)

        val bare = gateway.state("modu-dev")
        assertNull(bare.syncRevision)
        assertNull(bare.operation)
        server.verify()
    }

    @Test
    fun `sync posts the revision and the single deployment resource without prune`() {
        server.expect(requestTo("http://argocd-server.argocd.svc/api/v1/applications/modu-dev/sync"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.revision").value("c0ffee1234567890"))
            .andExpect(jsonPath("$.prune").value(false))
            .andExpect(jsonPath("$.resources[0].group").value("apps"))
            .andExpect(jsonPath("$.resources[0].kind").value("Deployment"))
            .andExpect(jsonPath("$.resources[0].name").value("point-service"))
            .andExpect(jsonPath("$.resources[0].namespace").value("modu"))
            .andExpect(jsonPath("$.resources.length()").value(1))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        gateway.sync("modu-dev", "c0ffee1234567890", listOf(ArgoResource("apps", "Deployment", "point-service", "modu")))
        server.verify()
    }
}
