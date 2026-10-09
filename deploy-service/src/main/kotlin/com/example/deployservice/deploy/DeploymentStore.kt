package com.example.deployservice.deploy

/**
 * 배포 기록 저장소. 운영 구현은 [JpaDeploymentStore](mysql-platform 의 modu-platform.deployment), 단위 테스트는 [InMemoryDeploymentStore].
 * 레코드는 불변이라 밖으로 나간 스냅샷은 그대로 안전하다.
 */
interface DeploymentStore {

    companion object {
        /** list() 가 한 번에 돌려주는 최대 건수의 상한(deploy.store.capacity 의 기본값). */
        const val MAX_LIST = 500
    }

    fun add(record: DeploymentRecord)

    /**
     * 그 서비스에 RUNNING 기록이 없을 때만 [record] 를 넣는다(넣었으면 true). 확인과 넣기가 한 단위다 — 운영(DB)은 서비스 비관적 잠금
     * `deploy:service:<name>` 을 쥔 한 트랜잭션이라 파드가 여러 개여도 서비스마다 RUNNING 은 하나뿐이다.
     */
    fun addIfNoneRunning(record: DeploymentRecord): Boolean

    /** 그 서비스에 RUNNING 기록이 있는가(운영은 master 에서 읽는다). */
    fun hasRunning(service: String): Boolean

    fun get(id: String): DeploymentRecord?

    /** [id] 의 기록을 [update] 로 바꾼 결과를 넣고 돌려준다. 없으면 null. 읽기-바꾸기-쓰기가 한 단위다. */
    fun update(id: String, update: (DeploymentRecord) -> DeploymentRecord): DeploymentRecord?

    /** 최신순(startedAt 내림차순). [service] 가 있으면 그 서비스만. */
    fun list(service: String? = null, limit: Int = MAX_LIST): List<DeploymentRecord>

    fun latest(service: String): DeploymentRecord? = list(service, 1).firstOrNull()

    fun latestSucceeded(service: String): DeploymentRecord?

    fun size(): Int
}

/**
 * 메모리 저장소. 최근 [capacity] 건만 남기고 오래된 것부터 버린다. 실행기·러너 단위 테스트용(운영은 DB).
 * 모든 접근은 한 락으로 직렬화한다.
 */
class InMemoryDeploymentStore(private val capacity: Int = DEFAULT_CAPACITY) : DeploymentStore {

    companion object {
        const val DEFAULT_CAPACITY = 50
    }

    private val lock = Any()
    private val records = LinkedHashMap<String, DeploymentRecord>()

    override fun add(record: DeploymentRecord) {
        synchronized(lock) {
            records[record.id] = record
            while (records.size > capacity) records.remove(records.keys.first())
        }
    }

    override fun addIfNoneRunning(record: DeploymentRecord): Boolean = synchronized(lock) {
        if (hasRunning(record.service)) return false
        add(record)
        true
    }

    override fun hasRunning(service: String): Boolean = synchronized(lock) {
        records.values.any { it.service == service && it.status == DeploymentStatus.RUNNING }
    }

    override fun get(id: String): DeploymentRecord? = synchronized(lock) { records[id] }

    override fun update(id: String, update: (DeploymentRecord) -> DeploymentRecord): DeploymentRecord? = synchronized(lock) {
        val current = records[id] ?: return null
        update(current).also { records[id] = it }
    }

    override fun list(service: String?, limit: Int): List<DeploymentRecord> = synchronized(lock) {
        records.values.reversed().asSequence()
            .filter { service == null || it.service == service }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    override fun latestSucceeded(service: String): DeploymentRecord? =
        list(service, Int.MAX_VALUE).firstOrNull { it.status == DeploymentStatus.SUCCEEDED }

    override fun size(): Int = synchronized(lock) { records.size }
}
