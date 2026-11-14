package com.example.deployservice.config

import io.temporal.worker.NonDeterministicException
import io.temporal.worker.WorkflowImplementationOptions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 워크플로 코드가 바뀐 뒤 진행 중 실행을 재생하다 비결정성이 나면 30분 스피너가 아니라 바로 실패로 끝나야 한다(README 의 운영 규칙). */
class TemporalConfigTest {
    @Test
    fun `non deterministic replay fails the workflow instead of retrying the task forever`() {
        val options = TemporalConfig().workflowImplementationCustomizer().customize(WorkflowImplementationOptions.newBuilder()).build()
        assertTrue(options.failWorkflowExceptionTypes.contains(NonDeterministicException::class.java))
    }
}
