# deploy-service

모두 시스템 "배포" 탭 백엔드(포트 8900). GHCR 태그를 보여 주고, 고른 태그를 modu_infra `k8s/overlays/dev/kustomization.yaml`
의 `images[].newTag` 에 커밋하고, Argo CD 로 그 Deployment 만 Sync 한 뒤 롤아웃을 지켜본다.
설정은 모두 `config-repo/deploy-service.yml`(비밀은 `{cipher}`) 에서 온다 — 이 저장소에는 기본값을 두지 않는다.

서비스 전체 설명(이력 DB·RW/RO 분리·동시 배포 차단 등)은 [저장소 README](../README.md) 에 있다. 이 문서는 **GitHub 자격**만 다룬다.

## GitHub 자격 — GitHub App

`modu_infra` 에 커밋하려면 GitHub 자격이 필요하다. 예전에는 사람 계정의 classic PAT(`admin:org`·`delete_repo`·`workflow`
범위까지 들고 있었다)을 썼지만, 지금은 **GitHub App 설치 토큰**을 쓴다:

- 설치 토큰은 **한 시간**이면 끝난다(서비스가 알아서 갱신한다 — 만료 5분 전).
- **설치한 저장소에만** 쓰기가 닿는다(여기서는 `modu_infra` 하나).
- 사람에 묶이지 않는다(퇴사·비밀번호 변경과 무관).

### 설정 키 (`config-repo/deploy-service.yml`)

```yaml
deploy:
  github:
    api-url: https://api.github.com
    owner: tear94fall
    infra-repo: modu_infra
    infra-branch: main
    kustomization-path: k8s/overlays/dev/kustomization.yaml
    app:
      id: "5263639"                 # App ID(또는 Client ID). 이 값이 있으면 앱 자격을 쓴다.
      installation-id: "169966383"  # 선택 — 비우면 GET /app/installations 로 찾아 기억한다.
      private-key: '{cipher}...'    # PKCS#8 PEM. 아래 "비밀키" 참고.
    token: '{cipher}...'            # 레거시 PAT — 임시 fallback. 앱으로 옮긴 뒤 지운다.
```

자격은 이 순서로 고른다([`DeployWiring.gitHubCredentials`](src/main/kotlin/com/example/deployservice/config/DeployWiring.kt)):

| 조건 | 쓰는 자격 | 보내는 헤더 |
| --- | --- | --- |
| `deploy.github.app.id` 가 있다 | GitHub App 설치 토큰(캐시·자동 갱신) | `Authorization: Bearer ghs_…` |
| 앱은 없고 `deploy.github.token` 이 있다 | 레거시 PAT | `Authorization: token ghp_…` |
| 둘 다 없다 | 없음(익명) | 헤더 없음 — 공개 저장소 읽기만, 시간당 60번 |

`Authorization` 은 **요청마다** 묻는다(클라이언트를 만들 때 한 번 박지 않는다) — 설치 토큰이 중간에 갱신되기 때문이다.

### App 만들기 (한 번)

1. GitHub → Settings → Developer settings → GitHub Apps → New GitHub App.
2. 권한은 **Repository permissions → Contents: Read and write** 하나만(Metadata: Read 는 자동으로 따라온다).
   Webhook 은 끈다. Organization/Account 권한은 주지 않는다.
3. 비밀키를 받는다(Generate a private key → `.pem` 내려받기).
4. **`modu_infra` 에만** 설치한다(Install App → Only select repositories → `modu_infra`).
   `/installation/repositories` 에 `tear94fall/modu_infra` 만 보이면 맞다.
5. App ID 와 비밀키를 `config-repo/deploy-service.yml` 에 넣는다(비밀키는 `{cipher}`).
   설치 ID 는 적지 않아도 된다 — 서비스가 `GET /app/installations` 로 찾는다(설치가 여러 개가 되면 적어 주는 게 맞다).

### 비밀키

GitHub 가 주는 `.pem` 은 **PKCS#1**(`-----BEGIN RSA PRIVATE KEY-----`)이라 JDK 의 `KeyFactory` 가 읽지 못한다.
PKCS#8 로 바꿔서 넣는다:

```sh
openssl pkcs8 -topk8 -nocrypt -in app.pem -out app.pk8.pem
```

PKCS#1 을 그대로 넣으면 **기동 때 바로** 이 변환 명령을 적은 한국어 메시지로 실패한다(배포 버튼을 눌렀을 때가 아니라).
키는 PEM(줄바꿈 있는 그대로)으로도, PEM 본문을 한 줄로 이어 붙인 base64 로도, PEM 전체를 base64 로 감싼 값으로도 넣을 수 있다.
암호가 걸린 키(`BEGIN ENCRYPTED PRIVATE KEY`)는 받지 않는다.

### 어떤 호출이 이 자격을 쓰는가

`api.github.com` 호출은 **모두** 같은 자격을 쓴다 — modu_infra 의 `kustomization.yaml` 읽기·커밋, 그리고 소스 저장소
(`modu_chat`·`modu_platform` 등)의 커밋 메시지 조회. 앱이 `modu_infra` 에만 설치돼 있어도 **설치 토큰으로 공개 저장소를
읽을 수 있다**(확인: 설치 토큰으로 `GET /repos/tear94fall/modu_chat/contents/README.md` → 200, 그런데
`GET /installation/repositories` 에는 `modu_infra` 만). 덕분에 익명 60/시간 대신 5000/시간을 쓰고 특별한 경로가 없다.
커밋 메시지 조회가 실패하면(403 등) 그 요청에서는 더 묻지 않고 **메시지 없이 태그 목록을 돌려준다** — 배포는 막히지 않는다.

### 레거시 토큰 지우기

`deploy.github.token` 은 마이그레이션 동안만 두는 fallback 이다. 앱으로 배포가 한 번 성공하면

1. `config-repo/deploy-service.yml` 에서 `deploy.github.token` 을 지우고,
2. GitHub 계정에서 그 PAT 를 폐기하고,
3. `DeployProperties.GitHub.token` 과 `GitHubCredentials.legacyToken` 경로를 코드에서 뺀다.

토큰만 있으면 기동 때 경고 로그가 남는다(`deploy.github.token(레거시 PAT)으로 인증합니다 — …`).

## GHCR 태그는 레지스트리 API 로 (인증 없음)

태그 목록은 **GitHub Packages REST API 를 쓰지 않는다**. 그 API 는 공개 패키지라도 인증을 요구하고,
**fine-grained 토큰·GitHub App 으로는 아예 쓸 수 없다**. 대신 저장소가 public 이라 레지스트리 API 로 익명으로 읽는다
([`GhcrRegistryGateway`](src/main/kotlin/com/example/deployservice/gateway/GhcrRegistryGateway.kt)):

1. `GET https://ghcr.io/token?scope=repository:<owner>/<pkg>:pull&service=ghcr.io` → 익명 pull 토큰(패키지별로 4분 캐시),
2. `GET /v2/<owner>/<pkg>/tags/list` → 태그 전부. `develop-<sha7>`·`master-<sha7>` 만 남긴다(움직이는 `develop`·`latest` 는 뺀다).
3. 화면에 보이는 날짜는 이미지 자신의 `created` 다: `GET /v2/.../manifests/<tag>` → (인덱스면 `platform.os != unknown` 인
   자식) → `GET /v2/.../manifests/<child>` → `config.digest` → `GET /v2/.../blobs/<digest>` 의 `created`.
   태그마다 3 요청이라 **(패키지, 태그)별로 캐시**한다(커밋 태그는 다시 찍히지 않는다). 못 읽으면 그 태그만 날짜 없이 나가고
   전체 조회는 실패하지 않는다. 한 번에 새로 읽는 태그 수는 60개로 막아 둔다.

GHCR 조회에는 GitHub 자격을 **붙이지 않는다** — 레지스트리 클라이언트는 따로 만든다.

## 테스트

```sh
cd deploy-service && ./gradlew test
```

네트워크·클러스터 없이 돈다. GitHub·GHCR 호출은 `MockRestServiceServer`, 이력 DB 는 H2, k8s 는 가짜 게이트웨이다.
App JWT 테스트는 RSA 키를 테스트 안에서 만들어 서명을 짝 공개키로 검증한다.
