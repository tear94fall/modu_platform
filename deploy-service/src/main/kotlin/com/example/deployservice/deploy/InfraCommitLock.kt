package com.example.deployservice.deploy

import com.example.deployservice.deploy.lock.PessimisticLock
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 인프라 저장소(modu_infra) kustomization.yaml 커밋 구간(sha 읽기 → 고치기 → PUT)을 서비스·파드 전체에서 하나씩만 돌게 한다.
 * 서로 다른 서비스 배포가 나란히 돌아도 같은 파일을 같은 sha 로 고쳐 두 번째가 409/422 로 깨지지 않게.
 * 운영 구현은 [JpaInfraCommitLock](DB 비관적 잠금), 단위 테스트는 [NONE] 이나 가짜.
 */
interface InfraCommitLock {
    fun <T> withLock(block: () -> T): T

    companion object {
        /** 잠그지 않는다(단일 스레드 단위 테스트용). */
        val NONE = object : InfraCommitLock {
            override fun <T> withLock(block: () -> T): T = block()
        }
    }
}

/**
 * [PessimisticLock] `deploy:infra-commit` 을 짧은 RW 트랜잭션 동안 쥔다. 커밋 하나(GitHub 호출 2~3번)는 몇 초면 끝나지만 다른 배포가 쥐고 있을 수 있어
 * 기다림은 기본(3초)보다 긴 [WAIT_SECONDS]. 이 트랜잭션 안에서는 배포 기록을 쓰지 않는다 — 기록 갱신(진행률)은 따로 커밋돼야 콘솔이 바로 본다.
 */
@Component
class JpaInfraCommitLock(private val pessimisticLock: PessimisticLock) : InfraCommitLock {

    companion object {
        const val NAME = "deploy:infra-commit"
        const val WAIT_SECONDS = 15
    }

    @Transactional(transactionManager = "rwTransactionManager")
    override fun <T> withLock(block: () -> T): T = pessimisticLock.withLock(NAME, WAIT_SECONDS, block)
}
