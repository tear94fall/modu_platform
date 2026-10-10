package com.example.deployservice.github

/**
 * GitHub 요청에 넣을 `Authorization` 헤더 값. **요청마다** 묻는다(설치 토큰은 한 시간이면 끝나므로 클라이언트를 만들 때
 * 한 번 박아 두면 안 된다 — [com.example.deployservice.config.DeployWiring] 의 requestInitializer 가 여기를 부른다).
 *
 * 고르는 순서는 [com.example.deployservice.config.DeployWiring.gitHubCredentials]:
 * GitHub App(`deploy.github.app.id`) → [GitHubAppCredentials], 없으면 레거시 PAT(`deploy.github.token`) → [legacyToken],
 * 둘 다 없으면 [ANONYMOUS](공개 저장소 읽기만 되고 시간당 60번).
 */
fun interface GitHubCredentials {

    /** 지금 쓸 `Authorization` 값. null 이면 헤더를 넣지 않는다(익명). */
    fun authorization(): String?

    companion object {
        /** 자격 없음 — 공개 저장소 읽기만 된다. */
        val ANONYMOUS: GitHubCredentials = GitHubCredentials { null }

        /** 레거시 개인 액세스 토큰(마이그레이션 끝나면 지운다). */
        fun legacyToken(token: String): GitHubCredentials = GitHubCredentials { "token $token" }
    }
}
