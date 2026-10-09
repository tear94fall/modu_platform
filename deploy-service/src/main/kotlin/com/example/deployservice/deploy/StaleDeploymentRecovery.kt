package com.example.deployservice.deploy

import com.example.deployservice.deploy.rw.DeploymentRwRepository
import com.example.deployservice.gateway.DeploymentSnapshot
import com.example.deployservice.gateway.KubernetesGateway
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 기동 때 RUNNING 으로 남은 기록을 닫는다. 배포 스레드는 프로세스와 함께 죽으므로(재시작·롤링 배포) DB 에 남은 RUNNING 은 진행 상태를 잃은 것이다.
 * 다만 실제로 끝까지 간 배포를 실패로 남기지 않게 지금 k8s 를 한 번 본다:
 *
 * - 기록의 서비스가 deploy-service 자신이고 Deployment 의 이미지 태그가 기록의 태그와 같다 → SUCCEEDED. 자기 배포는 새 파드가 뜨는 순간
 *   옛 파드(배포를 돌리던 쪽)가 사라지므로 늘 여기로 온다. 이 시점엔 새 파드가 아직 준비 전이라 롤아웃 완료까지는 보지 않는다.
 * - 다른 서비스여도 Deployment 가 기록의 태그로 롤아웃을 마쳤다 → SUCCEEDED.
 * - 그 밖(태그가 다름, 롤아웃 중, 조회 실패) → FAILED(예전과 같다). 실제 서비스 상태는 배포 탭의 서비스 목록이 보여 준다.
 *
 * RUNNING 행은 master([DeploymentRwRepository])에서 찾는다 — replica 는 늦을 수 있어 방금 남은 행을 놓칠 수 있다.
 */
@Component
class StaleDeploymentRecovery(
    private val repository: DeploymentRwRepository,
    private val store: DeploymentStore,
    private val clock: Clock,
    private val kubernetes: KubernetesGateway,
    @Value("\${spring.application.name:deploy-service}") private val selfName: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MESSAGE = "deploy-service 가 재시작돼 진행 상태를 잃었습니다. 서비스 상태는 목록에서 확인하세요."
        const val CONFIRMED = "재시작 뒤 확인: 실행 중 태그 일치"
    }

    override fun run(args: ApplicationArguments) {
        val stale = repository.findByStatus(DeploymentStatus.RUNNING.name).map { it.service to it.id }
        if (stale.isEmpty()) return
        val at = clock.instant()
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<String>()
        stale.forEach { (service, id) ->
            val live = runCatching { kubernetes.deployment(service) }
                .onFailure { log.warn("deployment {} lookup for stale {} failed: {}", service, id, it.message) }
                .getOrNull()
            store.update(id) { r ->
                if (r.status != DeploymentStatus.RUNNING) return@update r
                if (confirmed(r, live)) {
                    succeeded += id
                    r.copy(
                        status = DeploymentStatus.SUCCEEDED,
                        step = Step.DONE,
                        percent = Progress.DONE,
                        error = null,
                        finishedAt = at,
                        steps = r.steps.map { s ->
                            when (s.status) {
                                StepStatus.RUNNING, StepStatus.PENDING ->
                                    s.copy(status = StepStatus.SUCCEEDED, message = CONFIRMED, startedAt = s.startedAt ?: at, finishedAt = at)
                                else -> s
                            }
                        },
                    )
                } else {
                    failed += id
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
        }
        if (succeeded.isNotEmpty()) log.info("marked {} stale RUNNING deployment(s) SUCCEEDED after restart (running tag matches): {}", succeeded.size, succeeded)
        if (failed.isNotEmpty()) log.warn("marked {} stale RUNNING deployment(s) FAILED after restart: {}", failed.size, failed)
    }

    private fun confirmed(r: DeploymentRecord, live: DeploymentSnapshot?): Boolean {
        if (live == null || live.imageTag != r.tag) return false
        return r.service == selfName || live.rolledOut
    }
}
