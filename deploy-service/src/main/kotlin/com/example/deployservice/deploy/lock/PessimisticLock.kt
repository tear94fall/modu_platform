package com.example.deployservice.deploy.lock

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hibernate.Session
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/** [PessimisticLock] 이 잠금을 제한 시간 안에 얻지 못했다(다른 파드·스레드가 쥐고 있다). */
class PessimisticLockTimeoutException(val lockName: String, cause: Throwable? = null) :
    RuntimeException("잠금 $lockName 을 제한 시간 안에 얻지 못했습니다.", cause)

/**
 * 비관적 잠금: master `modu-platform.pessimistic_lock` 의 행 하나를 `SELECT … FOR UPDATE` 로 잠근다.
 * 파드가 여러 개여도(롤링 배포 중 두 파드, 복제본 2개 이상) 한 이름에 한 트랜잭션만 들어간다.
 *
 * 동작
 * 1. 이름의 행이 없으면 만든다. 따로 짧은 트랜잭션으로 바로 커밋한다.
 *    (같은 트랜잭션에서 만들면, 처음 동시에 들어온 두 요청이 서로를 기다리다 교착된다.)
 * 2. 호출한 트랜잭션에서 그 행을 잠그고 [block] 을 실행한다.
 * 3. 그 트랜잭션이 끝나면(커밋·롤백) 잠금이 풀린다. 그래서 "잠금 → 확인 → 쓰기 → 커밋"이 한 덩어리가 된다.
 *
 * 지킬 것
 * - RW 트랜잭션 안에서만 부를 수 있다(없으면 예외).
 * - 잠근 뒤 확인할 트랜잭션은 READ COMMITTED 로 연다. 이유는 [com.example.deployservice.deploy.JpaDeploymentStore.addIfNoneRunning] 참고.
 * - 기본 대기 3초(master 커넥션의 innodb_lock_wait_timeout). [waitSeconds] 를 주면 그 잠금만 대기 시간을 바꾸고 끝나면 되돌린다.
 * - 1초 넘게 기다리면 경고 로그, 시간을 넘기면 [PessimisticLockTimeoutException].
 */
@Component
class PessimisticLock(
    @Qualifier("rwTransactionManager") transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PersistenceContext(unitName = "RW")
    private lateinit var em: EntityManager

    private val requiresNew = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Volatile
    private var mysql: Boolean? = null

    companion object {
        const val SLOW_WAIT_MS = 1_000L
    }

    @Transactional(transactionManager = "rwTransactionManager", propagation = Propagation.MANDATORY)
    fun <T> withLock(name: String, waitSeconds: Int? = null, block: () -> T): T {
        require(name.length in 1..100) { "잠금 이름은 1~100자: $name" }
        ensureRow(name)
        val restore = waitSeconds?.takeIf { isMysql() }?.let { setLockWait(it) }
        try {
            val started = System.nanoTime()
            try {
                em.createNativeQuery("SELECT name FROM pessimistic_lock WHERE name = ?1 FOR UPDATE")
                    .setParameter(1, name)
                    .resultList
            } catch (e: Exception) {
                throw PessimisticLockTimeoutException(name, e)
            }
            val waitedMs = (System.nanoTime() - started) / 1_000_000
            if (waitedMs > SLOW_WAIT_MS) log.warn("pessimistic lock {} acquired after waiting {} ms", name, waitedMs)
            else log.debug("pessimistic lock {} acquired in {} ms", name, waitedMs)
        } finally {
            restore?.let { runCatching { setLockWait(it) }.onFailure { e -> log.warn("could not restore innodb_lock_wait_timeout: {}", e.message) } }
        }
        return block()
    }

    /**
     * 행이 없을 때만 만든다. 확인(잠그지 않는 읽기)도 넣기도 별도 트랜잭션(REQUIRES_NEW, 다른 연결)에서 한다 —
     * 호출자 트랜잭션에서 잠그기 전에 일반 SELECT 를 하면 InnoDB(REPEATABLE READ)가 그 시점 스냅숏을 잡아, 잠금을 얻은 뒤의 확인이
     * 그사이 커밋된 남의 행(방금 시작된 RUNNING)을 못 본다. 이미 있는 행에 바로 INSERT IGNORE 를 하지 않는 것은 남의 X 잠금을 기다리기 때문.
     */
    private fun ensureRow(name: String) {
        requiresNew.executeWithoutResult {
            val exists = em.createNativeQuery("SELECT COUNT(*) FROM pessimistic_lock WHERE name = ?1")
                .setParameter(1, name)
                .singleResult
                .let { (it as Number).toLong() > 0 }
            if (!exists) {
                em.createNativeQuery("INSERT IGNORE INTO pessimistic_lock (name, created_at) VALUES (?1, CURRENT_TIMESTAMP(6))")
                    .setParameter(1, name)
                    .executeUpdate()
            }
        }
    }

    /** 세션의 innodb_lock_wait_timeout 을 [seconds] 로 바꾸고 이전 값을 돌려준다. */
    private fun setLockWait(seconds: Int): Int {
        val previous = (em.createNativeQuery("SELECT @@SESSION.innodb_lock_wait_timeout").singleResult as Number).toInt()
        em.createNativeQuery("SET SESSION innodb_lock_wait_timeout = ${seconds.coerceIn(1, 3600)}").executeUpdate()
        return previous
    }

    private fun isMysql(): Boolean = mysql ?: em.unwrap(Session::class.java)
        .doReturningWork { it.metaData.databaseProductName.contains("MySQL", ignoreCase = true) }
        .also { mysql = it }
}
