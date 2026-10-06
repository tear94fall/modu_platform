package com.example.deployservice.gateway

import com.example.deployservice.api.UpstreamException
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Instant

/** Argo CD REST API. [client] 는 baseUrl(argocd.url)과 `Authorization: Bearer` 를 갖고 있다([DeployWiring]). */
class ArgoCdRestGateway(private val client: RestClient) : ArgoCdGateway {

    override fun refresh(application: String): ArgoApplicationState = call("refresh") {
        parse(client.get().uri("/api/v1/applications/{app}?refresh=normal", application).retrieve().body(JsonNode::class.java))
    }

    override fun state(application: String): ArgoApplicationState = call("상태 조회") {
        parse(client.get().uri("/api/v1/applications/{app}", application).retrieve().body(JsonNode::class.java))
    }

    override fun sync(application: String, revision: String, resources: List<ArgoResource>) = call("sync") {
        val body = mapOf(
            "revision" to revision,
            "prune" to false,
            "resources" to resources.map { mapOf("group" to it.group, "kind" to it.kind, "name" to it.name, "namespace" to it.namespace) },
        )
        client.post().uri("/api/v1/applications/{app}/sync", application)
            .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity()
        Unit
    }

    companion object {
        fun parse(node: JsonNode?): ArgoApplicationState {
            val status = node?.path("status") ?: return ArgoApplicationState(null, null)
            val op = status.path("operationState").takeIf { !it.isMissingNode && !it.isNull }?.let { s ->
                ArgoOperationState(
                    phase = s.path("phase").asText(""),
                    message = s.path("message").takeIf { !it.isMissingNode }?.asText(),
                    startedAt = s.path("startedAt").asText(null)?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    revision = s.path("operation").path("sync").path("revision").asText(null),
                )
            }
            return ArgoApplicationState(syncRevision = status.path("sync").path("revision").asText(null), operation = op)
        }
    }

    private inline fun <T> call(what: String, block: () -> T): T = try {
        block()
    } catch (e: RestClientResponseException) {
        throw UpstreamException("argocd", "Argo CD $what 실패 (${e.statusCode.value()}): ${e.responseBodyAsString.take(200)}", e)
    } catch (e: UpstreamException) {
        throw e
    } catch (e: Exception) {
        throw UpstreamException("argocd", "Argo CD $what 실패: ${e.message}", e)
    }
}
