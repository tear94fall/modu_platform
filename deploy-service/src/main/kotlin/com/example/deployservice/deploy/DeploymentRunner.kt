package com.example.deployservice.deploy

import com.example.deployservice.api.ApiException
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * 배포를 백그라운드로 돌린다. 서비스 이름마다 동시에 하나만 — 같은 서비스에 두 번째 요청이 오면 409 deploy_in_progress.
 * 서로 다른 서비스는 나란히 돈다(스레드 풀은 필요한 만큼 늘고 놀면 줄어든다).
 */
class DeploymentRunner(
    private val store: DeploymentStore,
    private val executor: DeploymentExecutor,
    private val pool: ExecutorService = defaultPool(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = ConcurrentHashMap<String, Future<*>>()
    private val lock = Any()

    companion object {
        fun defaultPool(): ExecutorService {
            val counter = AtomicInteger()
            return Executors.newCachedThreadPool { r ->
                Thread(r, "deploy-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }

    /** [record] 를 저장하고 실행을 예약한다. 그 서비스가 이미 배포 중이면 [ApiException] 409. */
    fun start(record: DeploymentRecord): DeploymentRecord = synchronized(lock) {
        val current = running[record.service]
        if (current != null && !current.isDone) {
            throw ApiException.conflict("deploy_in_progress", "${record.service} 는 이미 배포 중입니다.")
        }
        store.add(record)
        running[record.service] = pool.submit {
            try {
                executor.run(record.id)
            } catch (e: Throwable) {
                log.error("deployment {} runner crashed", record.id, e)
            }
        }
        log.info("deployment {} {} → {} by {} started", record.id, record.service, record.tag, record.by)
        record
    }

    fun isRunning(service: String): Boolean = running[service]?.let { !it.isDone } ?: false

    @PreDestroy
    fun shutdown() {
        pool.shutdownNow()
    }
}
