package com.example.deployservice.gateway

import com.example.deployservice.config.TemporalConverters
import com.example.deployservice.deploy.DeployProps.props
import com.example.deployservice.deploy.workflow.DeployInput
import com.example.deployservice.deploy.workflow.DeployWorkflow
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.workflow.Workflow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/** 진짜 SDK 계약을 고정한다 — start 의 runId, 실행 중/종료/없음의 status, 같은 ID 중복 시작의 예외 타입. 인메모리 Temporal. */
class WorkflowClientTemporalGatewayTest {

    /** 테스트용 워크플로 구현: 한 시간 잠들어 "실행 중" 상태를 유지한다(시간 건너뛰기는 꺼 둔다). */
    class SleepingDeployWorkflow : DeployWorkflow {
        override fun run(input: DeployInput) = Workflow.sleep(Duration.ofHours(1))
        override fun promote(by: String) = Unit
        override fun abort(by: String) = Unit
    }

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var gateway: WorkflowClientTemporalGateway
    private val input = DeployInput("dep-1", "point-service", "img", "develop-5708871", "me")

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(TemporalConverters.kotlinDataConverter()).build())
                .setUseTimeskipping(false)
                .build(),
        )
        env.newWorker(props.temporal.taskQueue).registerWorkflowImplementationTypes(SleepingDeployWorkflow::class.java)
        env.start()
        gateway = WorkflowClientTemporalGateway(env.workflowClient, props)
    }

    @AfterEach
    fun tearDown() = env.close()

    @Test
    fun `status is null for an unknown workflow id`() {
        assertNull(gateway.status("deploy-nope", null))
        assertNull(gateway.status("deploy-nope", "run-x"))
    }

    @Test
    fun `start returns the run id and the execution is RUNNING, a second start is rejected`() {
        val runId = gateway.start("deploy-point-service", input, Duration.ofMinutes(30))

        assertTrue(runId.isNotBlank())
        assertEquals(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING, gateway.status("deploy-point-service", null))
        assertEquals(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING, gateway.status("deploy-point-service", runId))
        assertThrows<WorkflowExecutionAlreadyStarted> { gateway.start("deploy-point-service", input, Duration.ofMinutes(30)) }
    }

    @Test
    fun `a terminated execution reports TERMINATED and its id can be started again`() {
        val runId = gateway.start("deploy-point-service", input, Duration.ofMinutes(30))
        env.workflowClient.newUntypedWorkflowStub("deploy-point-service").terminate("test")

        assertEquals(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED, gateway.status("deploy-point-service", runId))
        val second = gateway.start("deploy-point-service", input, Duration.ofMinutes(30))
        assertTrue(second != runId)
    }
}
