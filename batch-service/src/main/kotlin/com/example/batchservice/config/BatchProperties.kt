package com.example.batchservice.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * `batch.*` — 저장소 application.yml 의 기본값을 config-repo/batch-service.yml 이 덮어쓴다.
 * - run: 서비스 호출의 연결 시간, 잡 정의에 타임아웃이 없을 때 쓰는 기본 타임아웃, 콘솔이 줄 수 있는 상한.
 * - scheduler: 동시에 돌 수 있는 회차 수(스레드 풀).
 * - store: 목록 API 가 한 번에 돌려주는 최대 건수(DB 에는 전부 남는다).
 */
@ConfigurationProperties(prefix = "batch")
data class BatchProperties(
    val run: Run = Run(),
    val scheduler: Scheduler = Scheduler(),
    val store: Store = Store(),
) {
    data class Run(
        val connectTimeoutSeconds: Int = 5,
        val defaultTimeoutSeconds: Int = 600,
        val maxTimeoutSeconds: Int = 3600,
    )

    data class Scheduler(val poolSize: Int = 5)

    data class Store(val capacity: Int = 500)
}

/**
 * `modu.*` — config-repo 의 공통 application.yml 이 내려준다. [services] 는 서비스 이름 → 클러스터 안 주소(`http://commerce-service:8200`).
 * 잡의 대상 서비스는 이 목록 안에서만 고를 수 있고, 호출 주소도 여기서 푼다(콘솔이 주소를 적지 않는다).
 */
@ConfigurationProperties(prefix = "modu")
data class ModuProperties(
    val services: Map<String, String> = emptyMap(),
    val internalApi: InternalApi = InternalApi(),
) {
    data class InternalApi(val token: String = "") {
        override fun toString(): String = "InternalApi(token=***)"
    }

    fun serviceUrl(name: String): String? = services[name]?.trimEnd('/')
}

@Configuration
@EnableConfigurationProperties(BatchProperties::class, ModuProperties::class)
class BatchPropertiesConfig
