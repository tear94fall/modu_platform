package com.example.deployservice.config

import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter

/**
 * Temporal 페이로드 변환기 — Kotlin data class(DeployInput·RolloutView 등)를 Jackson 2 + Kotlin 모듈로. 운영 빈(TemporalConfig)과 테스트가 같은 것을 쓴다.
 * temporal-kotlin 의 KotlinObjectMapperFactory 는 쓰지 않는다: 그 아티팩트가 기대하는 jackson-module-kotlin 생성자가 Boot 4 BOM 의 2.x 와 달라 NoSuchMethodError 가 났다.
 * Temporal 의 기본 매퍼(java.time·Optional 등 등록)에 BOM 버전의 KotlinModule 만 더한다.
 */
object TemporalConverters {
    fun kotlinDataConverter(): DataConverter {
        val mapper = JacksonJsonPayloadConverter.newDefaultObjectMapper().registerModule(KotlinModule.Builder().build())
        return DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(JacksonJsonPayloadConverter(mapper))
    }
}
