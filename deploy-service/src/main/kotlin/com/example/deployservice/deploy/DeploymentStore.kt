package com.example.deployservice.deploy

import org.springframework.stereotype.Component

/**
 * 배포 기록 메모리 저장소. 최근 [capacity] 건만 남기고 오래된 것부터 버린다. 재시작하면 사라진다(dev 용 — 콘솔이 그렇게 안내한다).
 * 모든 접근은 한 락으로 직렬화한다. 레코드는 불변이라 밖으로 나간 스냅샷은 그대로 안전하다.
 */
@Component
class DeploymentStore(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        const val DEFAULT_CAPACITY = 50
    }

    private val lock = Any()
    private val records = LinkedHashMap<String, DeploymentRecord>()

    fun add(record: DeploymentRecord) {
        synchronized(lock) {
            records[record.id] = record
            while (records.size > capacity) records.remove(records.keys.first())
        }
    }

    fun get(id: String): DeploymentRecord? = synchronized(lock) { records[id] }

    /** [id] 의 기록을 [update] 로 바꾼 결과를 넣고 돌려준다. 없으면(이미 밀려났으면) null. */
    fun update(id: String, update: (DeploymentRecord) -> DeploymentRecord): DeploymentRecord? = synchronized(lock) {
        val current = records[id] ?: return null
        update(current).also { records[id] = it }
    }

    /** 최신순. [service] 가 있으면 그 서비스만. */
    fun list(service: String? = null, limit: Int = Int.MAX_VALUE): List<DeploymentRecord> = synchronized(lock) {
        records.values.reversed().asSequence()
            .filter { service == null || it.service == service }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    fun latest(service: String): DeploymentRecord? = list(service, 1).firstOrNull()

    fun latestSucceeded(service: String): DeploymentRecord? =
        list(service).firstOrNull { it.status == DeploymentStatus.SUCCEEDED }

    fun size(): Int = synchronized(lock) { records.size }
}
