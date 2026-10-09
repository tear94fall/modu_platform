package com.example.deployservice.deploy

import com.example.deployservice.config.DeployProperties
import com.example.deployservice.deploy.ro.DeploymentRoRepository
import com.example.deployservice.deploy.rw.DeploymentRwRepository
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional

/**
 * RW/RO 라우팅 규칙(JpaDeploymentStore KDoc): 쓰기·get·update(진행률 경로)는 master 저장소, list·latest·latestSucceeded·size(이력)는 replica 저장소.
 * 두 저장소를 mock 으로 두고 어느 쪽이 불리는지만 본다.
 */
class JpaDeploymentStoreRoutingTest {

    private val rw: DeploymentRwRepository = mock()
    private val ro: DeploymentRoRepository = mock()
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val properties = DeployProperties()
    private val store = JpaDeploymentStore(rw, ro, objectMapper, properties, mock())

    private val record = DeploymentRecord(id = "a", service = "point-service", tag = "develop-0000001", by = "임준섭", startedAt = Instant.parse("2026-10-07T08:00:00Z"))
    private val entity get() = store.toEntity(record)

    @Test
    fun `get reads the master`() {
        whenever(rw.findById("a")).thenReturn(Optional.of(entity))

        assertEquals(record, store.get("a"))
        verify(rw).findById("a")
        verifyNoInteractions(ro)
    }

    @Test
    fun `add and update go to the master`() {
        whenever(rw.findForUpdate("a")).thenReturn(entity)

        store.add(record)
        val next = store.update("a") { it.copy(percent = 50) }

        assertEquals(50, next?.percent)
        verify(rw).findForUpdate("a")
        verifyNoInteractions(ro)
    }

    @Test
    fun `list, latest, latestSucceeded and size read the replica`() {
        whenever(ro.findAllByOrderByStartedAtDescCreatedAtDesc(any())).thenReturn(listOf(entity))
        whenever(ro.findByServiceOrderByStartedAtDescCreatedAtDesc(eq("point-service"), any())).thenReturn(listOf(entity))
        whenever(ro.findFirstByServiceAndStatusOrderByStartedAtDescCreatedAtDesc(anyOrNull(), anyOrNull())).thenReturn(entity)
        whenever(ro.count()).thenReturn(7L)

        assertEquals(listOf("a"), store.list().map { it.id })
        assertEquals("a", store.latest("point-service")?.id)
        assertEquals("a", store.latestSucceeded("point-service")?.id)
        assertEquals(7, store.size())

        verify(ro).findAllByOrderByStartedAtDescCreatedAtDesc(any())
        verify(ro).findByServiceOrderByStartedAtDescCreatedAtDesc(eq("point-service"), any())
        verify(ro).findFirstByServiceAndStatusOrderByStartedAtDescCreatedAtDesc("point-service", DeploymentStatus.SUCCEEDED.name)
        verify(ro).count()
        verifyNoInteractions(rw)
    }
}
