package com.example.deployservice.deploy

import com.example.deployservice.deploy.rw.DeploymentRwRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 기동 때 RUNNING 으로 남은 기록을 FAILED 로 닫는다. 배포 스레드는 프로세스와 함께 죽으므로(재시작·롤링 배포) DB 에 남은 RUNNING 은
 * 진행 상태를 잃은 것이다. 실제 서비스가 떴는지는 배포 탭의 서비스 목록(k8s 조회)이 보여 준다.
 * RUNNING 행은 master([DeploymentRwRepository])에서 찾는다 — replica 는 늦을 수 있어 방금 남은 행을 놓칠 수 있다.
 */
@Component
class StaleDeploymentRecovery(
    private val repository: DeploymentRwRepository,
    private val store: DeploymentStore,
    private val clock: Clock,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MESSAGE = "deploy-service 가 재시작돼 진행 상태를 잃었습니다. 서비스 상태는 목록에서 확인하세요."
    }

    override fun run(args: ApplicationArguments) {
        val stale = repository.findByStatus(DeploymentStatus.RUNNING.name).map { it.id }
        if (stale.isEmpty()) return
        val at = clock.instant()
        stale.forEach { id ->
            store.update(id) { r ->
                r.copy(
                    status = DeploymentStatus.FAILED,
                    error = MESSAGE,
                    finishedAt = at,
                    steps = r.steps.map { s ->
                        when (s.status) {
                            StepStatus.RUNNING -> s.copy(status = StepStatus.FAILED, message = MESSAGE, finishedAt = at)
                            StepStatus.PENDING -> s.copy(status = StepStatus.FAILED)
                            else -> s
                        }
                    },
                )
            }
        }
        log.warn("marked {} stale RUNNING deployment(s) FAILED after restart: {}", stale.size, stale)
    }
}
