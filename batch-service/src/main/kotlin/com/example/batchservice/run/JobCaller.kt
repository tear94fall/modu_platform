package com.example.batchservice.run

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException
import java.time.Duration

/**
 * 서비스 호출 한 번의 결과. HTTP 로 답했으면 [status]·[body], 응답을 기다리다 [timeout] 을 넘겼으면 [timedOut], 연결조차 안 됐으면 [error].
 * 예외를 던지지 않는다 — 어떤 결과든 회차 행에 적어야 하므로.
 */
data class JobCallResult(
    val status: Int?,
    val body: String?,
    val timedOut: Boolean = false,
    val error: String? = null,
)

/** 서비스의 `POST {baseUrl}/api-internal/v1/jobs/{jobName}/run` 을 부른다. 테스트는 가짜로 바꿔 끼운다. */
fun interface JobCaller {
    fun call(baseUrl: String, jobName: String, runId: String, timeout: Duration): JobCallResult
}

/**
 * RestClient 구현. 호출마다 읽기 타임아웃이 다르므로(잡 정의의 timeoutSeconds) [clientFor] 가 타임아웃을 받아 클라이언트를 만든다 —
 * 운영은 [withTimeouts](요청 팩터리를 호출 때 만든다, 가벼운 객체), 테스트는 MockRestServiceServer 에 묶인 클라이언트를 돌려준다.
 * 4xx·5xx 도 예외가 아니라 상태·본문으로 돌려준다(onStatus 로 기본 예외를 끈다).
 */
class RestClientJobCaller(
    private val clientFor: (Duration) -> RestClient,
    private val internalToken: String,
) : JobCaller {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun call(baseUrl: String, jobName: String, runId: String, timeout: Duration): JobCallResult {
        return try {
            val response = clientFor(timeout).post()
                .uri("$baseUrl$RUN_PATH", jobName)
                .header(INTERNAL_TOKEN_HEADER, internalToken)
                .header(REQUEST_ID_HEADER, runId)
                .retrieve()
                .onStatus(HttpStatusCode::isError) { _, _ -> }
                .toEntity(String::class.java)
            JobCallResult(response.statusCode.value(), response.body)
        } catch (e: ResourceAccessException) {
            if (e.cause is SocketTimeoutException) {
                log.warn("job call timed out after {}s: {} {}", timeout.seconds, baseUrl, jobName)
                JobCallResult(null, null, timedOut = true)
            } else {
                log.warn("job call failed: {} {}: {}", baseUrl, jobName, e.message)
                JobCallResult(null, null, error = e.cause?.message ?: e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        const val RUN_PATH = "/api-internal/v1/jobs/{name}/run"
        const val INTERNAL_TOKEN_HEADER = "X-Internal-Token"
        const val REQUEST_ID_HEADER = "X-Request-Id"

        /** 운영용: 연결 시간은 고정, 읽기 시간은 호출마다. */
        fun withTimeouts(connectTimeout: Duration): (Duration) -> RestClient = { readTimeout ->
            RestClient.builder()
                .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(connectTimeout); setReadTimeout(readTimeout) })
                .build()
        }
    }
}
