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
 * 그 판단은 저장소([DeploymentStore.addIfNoneRunning], 운영은 `deployment` 표의 유니크 키)가 한다 — 파드가 둘 이상이어도(롤링 배포 중,
 * replicas > 1) 지켜진다. [running] 은 이 파드가 돌리는 작업의 기록일 뿐 판단에 쓰지 않는다.
 * 서로 다른 서비스는 나란히 돈다(스레드 풀은 필요한 만큼 늘고 놀면 줄어든다. 공유 잠금은 없다).
 */
class DeploymentRunner(
    private val store: DeploymentStore,
    private val executor: DeploymentExecutor,
    private val pool: ExecutorService = defaultPool(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = ConcurrentHashMap<String, Future<*>>()

    companion object {
        fun defaultPool(): ExecutorService {
            val counter = AtomicInteger()
            return Executors.newCachedThreadPool { r ->
                Thread(r, "deploy-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }

    /** [record] 를 저장하고 실행을 예약한다. 그 서비스가 이미 배포 중이면 [ApiException] 409. */
    fun start(record: DeploymentRecord): DeploymentRecord {
        if (!store.addIfNoneRunning(record)) throw ApiException.conflict("deploy_in_progress", "${record.service} 는 이미 배포 중입니다.")
        running[record.service] = pool.submit {
            try {
                executor.run(record.id)
            } catch (e: Throwable) {
                log.error("deployment {} runner crashed", record.id, e)
            }
        }
        log.info("deployment {} {} → {} by {} started", record.id, record.service, record.tag, record.by)
        return record
    }

    /** 이 파드에서 [service] 배포 스레드가 도는가(로컬 기록. 서비스당 하나 규칙은 [DeploymentStore.hasRunning]). */
    fun isRunningLocally(service: String): Boolean = running[service]?.let { !it.isDone } ?: false

    @PreDestroy
    fun shutdown() {
        pool.shutdownNow()
    }
}
