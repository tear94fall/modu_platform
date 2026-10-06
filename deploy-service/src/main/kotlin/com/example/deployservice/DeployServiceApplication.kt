package com.example.deployservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * 모두 시스템 "배포" 탭의 백엔드. GHCR 태그를 보여 주고, 고른 태그를 modu_infra 의 kustomization.yaml 에 커밋한 뒤
 * Argo CD 에 그 Deployment 만 Sync 시키고, 롤아웃이 끝날 때까지 진행률을 알려 준다.
 */
@SpringBootApplication
class DeployServiceApplication

fun main(args: Array<String>) {
    runApplication<DeployServiceApplication>(*args)
}
