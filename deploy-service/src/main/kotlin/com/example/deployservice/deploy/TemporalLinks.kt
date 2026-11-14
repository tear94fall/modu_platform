package com.example.deployservice.deploy

import com.example.deployservice.config.DeployProperties

/**
 * 배포 ↔ Temporal 워크플로를 잇는 이름들. 워크플로 ID 는 서비스당 하나(`deploy-<service>`)라 "서비스당 배포 하나" 의 2차 수문장이 되고,
 * 실행(run) 하나가 배포 기록 하나다([DeploymentRecord.workflowRunId]). 콘솔 링크는 맥 루프백 전용 Temporal UI(deploy.temporal.public-url).
 */
object TemporalLinks {

    fun workflowId(service: String): String = "deploy-$service"

    /** 실행 id 가 없는 기록(옛 기록·스레드 시절)은 링크가 없다. */
    fun url(temporal: DeployProperties.Temporal, record: DeploymentRecord): String? {
        val runId = record.workflowRunId ?: return null
        return "${temporal.publicUrl.trimEnd('/')}/namespaces/${temporal.namespace}/workflows/${workflowId(record.service)}/$runId/history"
    }

    fun decorate(temporal: DeployProperties.Temporal, record: DeploymentRecord): DeploymentRecord =
        record.copy(workflowUrl = url(temporal, record))
}
