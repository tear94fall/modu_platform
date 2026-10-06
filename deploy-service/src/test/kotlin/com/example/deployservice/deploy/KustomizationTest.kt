package com.example.deployservice.deploy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** modu_infra/k8s/overlays/dev/kustomization.yaml 에서 가져온 표본으로 글자 그대로의 치환을 본다. */
class KustomizationTest {

    private val sample = """
        |# dev 오버레이: 로컬 Colima(k3s, context colima, 1 노드 — 2026-10-06 까지는 Docker Desktop 이었다). 네임스페이스는 base 와 같은 modu.
        |apiVersion: kustomize.config.k8s.io/v1beta1
        |kind: Kustomization
        |
        |namespace: modu
        |
        |resources:
        |  - ../../base
        |  - loadbalancers.yaml
        |  - storageclass.yaml     # Colima(k3s)용 StorageClass `standard` (2026-10-06)
        |
        |# 이미지 태그. base 는 develop(브랜치 롤링 태그). CI 가 올린 태그(예: sha-…)로 바꿔 apply 하면 그 Deployment 만 롤아웃된다.
        |images:
        |  - name: ghcr.io/tear94fall/modu-platform/config-service
        |    newTag: develop
        |  - name: ghcr.io/tear94fall/modu-platform/gateway-service
        |    newTag: develop
        |  - name: ghcr.io/tear94fall/modu-chat/storage-service
        |    newTag: develop-5708871  # AWS SDK v2 + Ceph RGW (modu_chat #423). 움직이는 태그(develop)는 노드의 옛 MinIO 이미지가 쓰인다 — 항상 커밋 태그로
        |  - name: ghcr.io/tear94fall/modu-chat/point-service
        |    newTag: develop
        |  - name: ghcr.io/tear94fall/modu-commerce/web
        |    newTag: develop-23fc7a4  # 움직이는 태그(develop)는 imagePullPolicy IfNotPresent 때문에 노드의 옛 이미지가 쓰인다 — 항상 커밋 태그로
        |
        |# 복제 수. 1 노드라 gateway·config 만 2(롤링 재배포 때 끊기지 않게), 나머지는 1.
        |replicas:
        |  - name: config-service
        |    count: 1
        |  - name: point-service
        |    count: 1
        |
        |#patches:
        |#  - path: pinpoint-agent-patch.yaml
        |""".trimMargin()

    @Test
    fun `reads newTag per image`() {
        val tags = Kustomization.tags(sample)
        assertEquals("develop", tags["ghcr.io/tear94fall/modu-platform/config-service"])
        assertEquals("develop-5708871", tags["ghcr.io/tear94fall/modu-chat/storage-service"])
        assertEquals("develop-23fc7a4", tags["ghcr.io/tear94fall/modu-commerce/web"])
        assertEquals(5, tags.size)
        assertEquals("develop", Kustomization.currentTag(sample, "ghcr.io/tear94fall/modu-chat/point-service"))
        // replicas 의 name 은 images 가 아니다.
        assertNull(Kustomization.currentTag(sample, "config-service"))
        assertNull(Kustomization.currentTag(sample, "ghcr.io/tear94fall/modu-admin/modu-admin"))
    }

    @Test
    fun `replaces only the newTag line and keeps the trailing comment`() {
        val out = Kustomization.replaceTag(sample, "ghcr.io/tear94fall/modu-chat/storage-service", "develop-abcdef0")

        val expected = sample.replace(
            "    newTag: develop-5708871  # AWS SDK v2",
            "    newTag: develop-abcdef0  # AWS SDK v2",
        )
        assertEquals(expected, out)
        assertEquals(1, diffLines(sample, out))
    }

    @Test
    fun `replaces an unpinned tag without touching other services`() {
        val out = Kustomization.replaceTag(sample, "ghcr.io/tear94fall/modu-chat/point-service", "develop-5708871")

        assertEquals("develop-5708871", Kustomization.currentTag(out, "ghcr.io/tear94fall/modu-chat/point-service"))
        assertEquals("develop", Kustomization.currentTag(out, "ghcr.io/tear94fall/modu-platform/config-service"))
        assertEquals("develop", Kustomization.currentTag(out, "ghcr.io/tear94fall/modu-platform/gateway-service"))
        assertEquals(1, diffLines(sample, out))
        // 주석·replicas·끝 줄바꿈까지 그대로
        assertEquals(sample.lines().size, out.lines().size)
        assertEquals(sample.substringAfter("replicas:"), out.substringAfter("replicas:"))
    }

    @Test
    fun `keeps quotes when the value is quoted`() {
        val quoted = "images:\n  - name: ghcr.io/x/y\n    newTag: \"develop\"\n"
        assertEquals("images:\n  - name: ghcr.io/x/y\n    newTag: \"develop-1234567\"\n", Kustomization.replaceTag(quoted, "ghcr.io/x/y", "develop-1234567"))
        assertEquals("develop", Kustomization.currentTag(quoted, "ghcr.io/x/y"))
    }

    @Test
    fun `appends a new entry at the end of the images list when the image is missing`() {
        val out = Kustomization.replaceTag(sample, "ghcr.io/tear94fall/modu-platform/deploy-service", "develop-1111111")

        assertEquals("develop-1111111", Kustomization.currentTag(out, "ghcr.io/tear94fall/modu-platform/deploy-service"))
        val lines = out.lines()
        val webTag = lines.indexOfFirst { it.startsWith("    newTag: develop-23fc7a4") }
        assertEquals("  - name: ghcr.io/tear94fall/modu-platform/deploy-service", lines[webTag + 1])
        assertEquals("    newTag: develop-1111111", lines[webTag + 2])
        assertEquals("", lines[webTag + 3]) // 빈 줄과 replicas 주석은 그대로 뒤에
        assertEquals(sample.lines().size + 2, lines.size)
    }

    @Test
    fun `same tag is a no-op and a file without images fails`() {
        assertEquals(sample, Kustomization.replaceTag(sample, "ghcr.io/tear94fall/modu-chat/storage-service", "develop-5708871"))
        assertThrows(IllegalArgumentException::class.java) { Kustomization.replaceTag("resources:\n  - x\n", "ghcr.io/x/y", "develop-1234567") }
    }

    private fun diffLines(a: String, b: String): Int = a.lines().zip(b.lines()).count { (x, y) -> x != y } + kotlin.math.abs(a.lines().size - b.lines().size)
}
