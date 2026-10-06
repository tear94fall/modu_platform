package com.example.deployservice.api

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

/** 모든 오류 응답의 몸체: `{ "error": "<code>", "message": "<한국어 설명>" }`. */
data class ErrorResponse(val error: String, val message: String)

/** 요청이 잘못됐거나(4xx) 외부 시스템이 답하지 않을 때(5xx) 컨트롤러·서비스가 던진다. */
class ApiException(val status: HttpStatus, val error: String, message: String) : RuntimeException(message) {
    companion object {
        fun badRequest(error: String, message: String) = ApiException(HttpStatus.BAD_REQUEST, error, message)
        fun notFound(error: String, message: String) = ApiException(HttpStatus.NOT_FOUND, error, message)
        fun conflict(error: String, message: String) = ApiException(HttpStatus.CONFLICT, error, message)
        fun upstream(error: String, message: String) = ApiException(HttpStatus.BAD_GATEWAY, error, message)
    }
}

/** GitHub·Argo CD·API 서버 호출이 실패했다. [system] 은 오류 코드에 들어간다(github_error, argocd_error, kubernetes_error). */
class UpstreamException(val system: String, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.status).body(ErrorResponse(e.error, e.message ?: e.error))

    @ExceptionHandler(UpstreamException::class)
    fun upstream(e: UpstreamException): ResponseEntity<ErrorResponse> {
        log.warn("upstream {} failed: {}", e.system, e.message)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse("${e.system}_error", e.message ?: "${e.system} 호출에 실패했습니다."))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("bad_request", "요청 본문을 읽을 수 없습니다."))

    @ExceptionHandler(NoResourceFoundException::class)
    fun noResource(e: NoResourceFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("not_found", "없는 경로입니다."))

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("unexpected error", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse("internal_error", "처리 중 오류가 났습니다."))
    }
}
