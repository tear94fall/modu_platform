package com.example.batchservice.gateway

import com.example.batchservice.api.UpstreamException
import com.example.batchservice.job.ServiceJobView
import org.springframework.core.ParameterizedTypeReference
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/** 서비스가 돌릴 수 있는 잡 목록(`GET {service}/api-internal/v1/jobs`). 콘솔은 이 목록에서 골라 등록한다(경로를 직접 적지 않는다). */
fun interface ServiceJobsGateway {
    fun jobs(service: String, baseUrl: String): List<ServiceJobView>
}

class RestClientServiceJobsGateway(private val client: RestClient) : ServiceJobsGateway {
    override fun jobs(service: String, baseUrl: String): List<ServiceJobView> =
        try {
            client.get().uri("$baseUrl/api-internal/v1/jobs").retrieve()
                .body(object : ParameterizedTypeReference<List<ServiceJobView>>() {}) ?: emptyList()
        } catch (e: RestClientResponseException) {
            throw UpstreamException(service, "$service 가 잡 목록에 ${e.statusCode.value()} 로 답했습니다.", e, e.statusCode.value())
        } catch (e: Exception) {
            throw UpstreamException(service, "$service 의 잡 목록을 읽지 못했습니다: ${e.message}", e)
        }
}
