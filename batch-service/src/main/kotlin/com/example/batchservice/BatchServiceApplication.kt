package com.example.batchservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * 모두 시스템 "배치" 탭의 백엔드. 잡 정의(어느 서비스의 어느 잡을 어떤 주기로)와 실행 이력을 mysql-platform 에 두고,
 * cron 에 맞춰(또는 수동으로) 그 서비스의 `POST /api-internal/v1/jobs/{name}/run` 을 불러 결과를 기록한다.
 * 작업 본문은 데이터를 가진 서비스에 있고, 여기는 "언제 누가 돌렸나"만 맡는다.
 */
@SpringBootApplication
class BatchServiceApplication

fun main(args: Array<String>) {
    runApplication<BatchServiceApplication>(*args)
}
