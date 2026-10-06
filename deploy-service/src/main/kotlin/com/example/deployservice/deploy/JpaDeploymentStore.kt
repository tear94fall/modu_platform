package com.example.deployservice.deploy

import com.example.deployservice.config.DeployProperties
import com.example.deployservice.deploy.ro.DeploymentRoRepository
import com.example.deployservice.deploy.rw.DeploymentRwRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 배포 기록을 mysql-platform 의 `modu-platform.deployment` 에 둔다(재시작해도 남는다). 전부 보관하고 list() 만 `deploy.store.capacity`(기본 500)로 자른다.
 * steps/rollout 은 Spring 의 ObjectMapper 로 JSON 문자열 ↔ 객체(ISO-8601 UTC 시각). 쓰기는 모두 한 줄 UPDATE/INSERT 라 롤아웃 폴링(초당 몇 번)도 가볍다.
 *
 * 읽기/쓰기 분리(RW = master `mysql-platform`, RO = replica `mysql-platform-replica`, 계정 platform_ro):
 *
 * | 메서드 | DB | 이유 |
 * |---|---|---|
 * | [add], [update] | master([DeploymentRwRepository]) | 쓰기. update 는 FOR UPDATE 로 잠그고 읽는다 |
 * | [get] | master | 진행률 경로 — 콘솔이 배포 직후 2초마다 한 건을 폴링한다. 복제 지연이 있으면 방금 쓴 진행률이 안 보이거나(404) 뒤로 간다 |
 * | 재시작 복구([StaleDeploymentRecovery]) | master | 방금 RUNNING 으로 남은 행을 놓치면 안 된다 |
 * | [list], [latest], [latestSucceeded], [size] | replica([DeploymentRoRepository]) | 이력 페이지·서비스 표. 몇 초 늦어도 되고 master 부하를 덜어 준다 |
 *
 * 그래서 "방금 쓴 것을 바로 다시 읽어야 하는" 새 조회는 RO 가 아니라 RW 저장소에 둔다. 롤백 대상([latestSucceeded])은 배포가 끝난 지
 * 한참 뒤에 사람이 누르는 것이라 replica 로 충분하다.
 */
@Component
class JpaDeploymentStore(
    private val rw: DeploymentRwRepository,
    private val ro: DeploymentRoRepository,
    private val objectMapper: ObjectMapper,
    properties: DeployProperties,
) : DeploymentStore {

    private val maxList = properties.store.capacity.coerceIn(1, DeploymentStore.MAX_LIST)

    // ---- master(RW) ------------------------------------------------------------------------------------------------------

    @Transactional
    override fun add(record: DeploymentRecord) {
        rw.save(toEntity(record))
    }

    /** master 에서 읽는다(진행률 폴링 — 복제 지연 없이 방금 쓴 값). */
    @Transactional(readOnly = true)
    override fun get(id: String): DeploymentRecord? = rw.findById(id).orElse(null)?.let(::toRecord)

    /** 한 트랜잭션에서 master 의 행을 잠그고(FOR UPDATE) 읽어 [update] 를 적용해 쓴다. */
    @Transactional
    override fun update(id: String, update: (DeploymentRecord) -> DeploymentRecord): DeploymentRecord? {
        val entity = rw.findForUpdate(id) ?: return null
        val next = update(toRecord(entity))
        require(next.id == id) { "배포 기록 id 는 바꿀 수 없습니다: $id → ${next.id}" }
        apply(entity, next)
        rw.save(entity)
        return next
    }

    // ---- replica(RO) -----------------------------------------------------------------------------------------------------

    /** replica 에서 읽는다(이력 목록. [latest] 도 이것을 거친다). */
    @Transactional(transactionManager = RO_TX, readOnly = true)
    override fun list(service: String?, limit: Int): List<DeploymentRecord> {
        val page = PageRequest.of(0, limit.coerceIn(1, maxList))
        val rows = if (service == null) ro.findAllByOrderByStartedAtDescCreatedAtDesc(page)
        else ro.findByServiceOrderByStartedAtDescCreatedAtDesc(service, page)
        return rows.map(::toRecord)
    }

    @Transactional(transactionManager = RO_TX, readOnly = true)
    override fun latestSucceeded(service: String): DeploymentRecord? =
        ro.findFirstByServiceAndStatusOrderByStartedAtDescCreatedAtDesc(service, DeploymentStatus.SUCCEEDED.name)?.let(::toRecord)

    @Transactional(transactionManager = RO_TX, readOnly = true)
    override fun size(): Int = ro.count().toInt()

    companion object {
        const val RO_TX = "roTransactionManager"
    }

    // ---- 변환 -------------------------------------------------------------------------------------------------------------

    internal fun toEntity(r: DeploymentRecord): DeploymentEntity = DeploymentEntity(id = r.id).also { apply(it, r) }

    private fun apply(e: DeploymentEntity, r: DeploymentRecord) {
        e.service = r.service
        e.tag = r.tag
        e.previousTag = r.previousTag
        e.byName = r.by
        e.byId = r.byId
        e.status = r.status.name
        e.step = r.step.name
        e.percent = r.percent
        e.startedAt = r.startedAt
        e.finishedAt = r.finishedAt
        e.error = r.error
        e.commitSha = r.commit?.sha
        e.commitUrl = r.commit?.url
        e.stepsJson = objectMapper.writeValueAsString(r.steps)
        e.rolloutJson = r.rollout?.let { objectMapper.writeValueAsString(it) }
    }

    internal fun toRecord(e: DeploymentEntity): DeploymentRecord = DeploymentRecord(
        id = e.id,
        service = e.service,
        tag = e.tag,
        previousTag = e.previousTag,
        by = e.byName,
        byId = e.byId,
        startedAt = e.startedAt,
        finishedAt = e.finishedAt,
        status = DeploymentStatus.valueOf(e.status),
        step = Step.valueOf(e.step),
        percent = e.percent,
        steps = objectMapper.readValue<List<StepRecord>>(e.stepsJson),
        rollout = e.rolloutJson?.let { objectMapper.readValue<RolloutView>(it) },
        commit = e.commitSha?.let { sha -> CommitView(sha, e.commitUrl ?: "") },
        error = e.error,
    )
}
