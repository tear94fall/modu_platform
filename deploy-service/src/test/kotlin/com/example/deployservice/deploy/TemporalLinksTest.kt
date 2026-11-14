package com.example.deployservice.deploy

import com.example.deployservice.config.DeployProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/** 워크플로 ID 와 콘솔 "Temporal 에서 보기" 링크. 실행 id 가 없는(옛) 기록은 링크가 없다. */
class TemporalLinksTest {

    private val temporal = DeployProperties.Temporal(namespace = "platform", publicUrl = "http://localhost:8233/")

    private fun record(runId: String?) =
        DeploymentRecord(id = "dep-1", service = "point-service", tag = "develop-5708871", by = "me", startedAt = Instant.EPOCH, workflowRunId = runId)

    @Test
    fun `workflow id is deploy dash service`() {
        assertEquals("deploy-point-service", TemporalLinks.workflowId("point-service"))
    }

    @Test
    fun `url points at the run history`() {
        assertEquals(
            "http://localhost:8233/namespaces/platform/workflows/deploy-point-service/run-1/history",
            TemporalLinks.url(temporal, record("run-1")),
        )
    }

    @Test
    fun `no run id means no url`() {
        assertNull(TemporalLinks.url(temporal, record(null)))
        assertNull(TemporalLinks.decorate(temporal, record(null)).workflowUrl)
    }

    @Test
    fun `decorate fills workflowUrl and keeps everything else`() {
        val r = record("run-1")
        val d = TemporalLinks.decorate(temporal, r)
        assertEquals(r, d.copy(workflowUrl = null))
        assertEquals("http://localhost:8233/namespaces/platform/workflows/deploy-point-service/run-1/history", d.workflowUrl)
    }
}
