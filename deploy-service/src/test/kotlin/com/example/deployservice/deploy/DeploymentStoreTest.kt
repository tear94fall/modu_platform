package com.example.deployservice.deploy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class DeploymentStoreTest {

    private fun record(id: String, service: String = "point-service", status: DeploymentStatus = DeploymentStatus.RUNNING, previous: String? = null) =
        DeploymentRecord(id = id, service = service, tag = "develop-$id", previousTag = previous, by = "me", startedAt = Instant.EPOCH, status = status)

    @Test
    fun `keeps the newest 50 and lists newest first`() {
        val store = DeploymentStore()
        (1..60).forEach { store.add(record("%07d".format(it))) }

        assertEquals(50, store.size())
        assertNull(store.get("0000010"))
        assertEquals("0000060", store.list().first().id)
        assertEquals("0000011", store.list().last().id)
        assertEquals(3, store.list(limit = 3).size)
    }

    @Test
    fun `filters by service and finds the last succeeded`() {
        val store = DeploymentStore()
        store.add(record("a", status = DeploymentStatus.SUCCEEDED, previous = "develop-0000001"))
        store.add(record("b", service = "gateway-service", status = DeploymentStatus.SUCCEEDED, previous = "develop-0000002"))
        store.add(record("c", status = DeploymentStatus.FAILED, previous = "develop-0000003"))
        store.add(record("d"))

        assertEquals(listOf("d", "c", "a"), store.list("point-service").map { it.id })
        assertEquals("d", store.latest("point-service")?.id)
        assertEquals("a", store.latestSucceeded("point-service")?.id)
        assertNull(store.latestSucceeded("nope"))
    }

    @Test
    fun `update replaces the record in place`() {
        val store = DeploymentStore()
        store.add(record("a"))

        val updated = store.update("a") { it.copy(percent = 42).withStep(Step.SYNC) { s -> s.copy(status = StepStatus.RUNNING) } }

        assertEquals(42, updated?.percent)
        assertEquals(StepStatus.RUNNING, store.get("a")!!.steps.first { it.name == Step.SYNC }.status)
        assertNull(store.update("zzz") { it })
    }
}
