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

## Temporal 워크플로 (2026-11-14)

배포 한 건은 Temporal 워크플로 **`deploy-<service>`**(네임스페이스 `platform`, 태스크 큐 `deploy`)의 실행 하나다 — `deploy/workflow/`.
COMMIT → SYNC → ROLLOUT 단계와 문구는 예전 스레드 실행기와 같지만, 기다림은 Temporal 타이머이고 상태는 Temporal 이 들고 있어
deploy-service 파드가 죽어도(자기 자신을 배포할 때 포함) 새 파드의 워커가 이어서 끝낸다. 재시작 복구 코드는 없다.

- 게이트웨이(GitHub·Argo CD·k8s)는 액티비티(`DeployActivitiesImpl`)가 감싼다. sha 충돌은 재시도 가능 실패(5회), 그 외는 재시도 안 함. 기록(MySQL `deployment`)은 로컬 액티비티.
- 수문장 둘: `deployment` 유니크 키(running_service) → 워크플로 ID(`DeploymentStarter`). **고아 RUNNING 기록**(워크플로가 기록을 못 닫고 사라진 경우 —
  타임아웃·terminate·DB 다운·스레드 시절 기록)은 다음 배포 시작 때 Temporal 에 실행 상태를 물어 없으면 FAILED(`워크플로가 없어 닫음…`)로 닫고 진행한다.
- 기록의 `workflowRunId`(DDL 2026-11-15) 로 콘솔이 "Temporal 에서 보기" 링크(`deploy.temporal.public-url`, 맥 127.0.0.1:8233 전용 UI)를 만든다.
- 헬스 `temporal` 은 `/actuator/health` 에만 — readiness 아님. Temporal 이 끊기면 배포 시작만 503 `temporal_unavailable`.
- 설정(config-repo `deploy-service.yml`): `spring.temporal.connection.target`(`temporal:7233`), `spring.temporal.namespace`, `deploy.temporal.{namespace,task-queue,public-url}`.
- **맥에서 띄울 때**: `TEMPORAL_TARGET=127.0.0.1:7233`(overlays/dev `temporal-lb`) 와 **`TEMPORAL_TASK_QUEUE=deploy-local`** — dev 파드의 워커와 큐를 나눠 먹지 않게.
  로컬 워커로 시작한 배포는 로컬 프로세스를 끄면 고아가 되고, 다음 배포 시작 때 닫힌다.
- 페이로드는 Jackson 2 + KotlinModule(`TemporalConverters`). `temporal-kotlin` 은 쓰지 않는다(Boot 4 BOM 의 jackson-module-kotlin 과 생성자 불일치).
- **워크플로 코드를 바꿀 때(운영 규칙)**: 자기 자신을 배포하면 새 파드가 진행 중인 `deploy-deploy-service` 실행을 **재생**한다. `DeployWorkflowImpl` 의
  액티비티 호출 순서·타이머·분기를 바꿨다면 (a) `Workflow.getVersion` 으로 옛 실행과 새 실행을 갈라 두거나, (b) 배포 전에 `deploy-deploy-service` 가
  실행 중이 아닌지(배포 탭 서비스 표·Temporal UI) 확인한다. 비결정성이 나면 `TemporalConfig.workflowImplementationCustomizer` 가 워크플로를 바로 실패로
  끝내고(30분 스피너 대신), 그 기록은 다음 배포 시작 때 고아 정리가 닫는다. 3단계(카나리 Signal)가 첫 사례다.
- 고아 정리의 레이스 보호: runId 가 없는 RUNNING 기록은 2분(`ORPHAN_GRACE`) 동안 "시작 진행 중" 으로 보고 닫지 않는다(두 운영자가 동시에 눌렀을 때 살아 있는 배포를 FAILED 로 오판하지 않게).

## 카나리 배포 (2026-11-15)

앱 18개는 modu_infra 에서 같은 이름의 **Rollout**(Argo Rollouts, `workloadRef`)이 파드를 가진다. 배포 탭의 커밋·Sync 는 그대로(Deployment 템플릿)이고, 워크플로의 ROLLOUT 단계가
Rollout 을 읽는다(`Workflow.getVersion("rollout-v2")` — 옛 Deployment 폴링 경로는 전환 전 실행의 재생용으로 남겨 두었다, 다음 워크플로 변경 때 제거).

- **DIRECT**(기존 "배포"): `promoteFull` 로 단계를 전부 건너뛰고 끝까지. 파드 1개 서비스는 단계가 없어 지금과 같다. Sync 직후의 `promoteFull` 은 컨트롤러가 새 템플릿을 보기 전에 닿으면 지워지므로, 폴링 중 단계에서 멈춘 것을 보면 다시 보낸다(멱등).
- **CANARY**(`mode: CANARY`, 파드 2개 이상 **이고 단계가 있는** Rollout — `canaryCapable`; steps 없이 2개면 멈추지 않고 100% 까지 가 버리므로 400): Rollout 이 단계에서 멈추면(`pauseConditions`) 기록 `canary.waiting=true` 로 사람을 기다린다 — `POST /deployments/{id}/promote|abort`(Signal).
  대기 중 `GET /deployments/{id}` 는 Rollout·파드를 지금 읽어 `canary.live` 로 덧붙이고, `GET /deployments/{id}/errors?since=` 는 새 파드의 ERROR 로그(OpenSearch `deploy.logs.*`)를 돌려준다.
  워크플로는 1시간마다 깨어나 새 파드가 CrashLoop 이면 FAILED, 대기가 **배포 전체로 7일**(단계마다가 아니다 — 실행 타임아웃 8일 안)이 지나면 시스템이 중단 경로를 밟는다(옛 버전으로 되돌리고 git 태그 원복, 기록 ABORTED "7일 대기 상한"). `canary.live.stepWeights` 로 콘솔이 "다음 단계 → W%" 를 계산한다.
- **중단**: Rollout abort → **먼저** 이전 태그 커밋 + Sync(그 뒤 무엇이 실패해도 git 은 옛 태그) → 템플릿이 옛 태그로 돌아와 Healthy → 기록 **ABORTED**(실패가 아니다). 커밋이 실패하면 FAILED 메시지에 "git 에는 새 태그가 남아 있다" 를 적는다. 단계 이력은 `canary.history`.
- 단계 규칙(파드 N → `floor(100k/N)`%, 5% 미만이면 묶음)은 modu_infra `k8s/rollouts/steps.sh` 가 git 에 적는다 — 여기선 계산하지 않는다.
- **기록 없이 멈춘 Rollout**(배포 탭 밖에서 템플릿이 바뀐 경우): 서비스 표 `status PAUSED`·`rolloutPaused.deploymentId null` → `POST /services/{name}/rollout/promote|abort` 로 직접 푼다(git 은 건드리지 않는다). abort 뒤 Rollout 은 Degraded + `abort=true` 로 남는데(`rolloutAborted`), 그때의 `rollout/promote` 는 `{"abort": false}`(= argo rollouts retry) 로 첫 단계부터 다시 돌려 멈추게 한다 — 콘솔 서비스 행의 "재시도".
- 실행 타임아웃: DIRECT 30분, CANARY 8일(서버 안전망). 카나리 대기 중 deploy-service 가 재시작돼도 대기는 Temporal 이 들고 있다.

## 테스트

```sh
cd deploy-service && ./gradlew test
```

네트워크·클러스터 없이 돈다. GitHub·GHCR 호출은 `MockRestServiceServer`, 이력 DB 는 H2, k8s 는 가짜 게이트웨이, Temporal 은 인메모리
(`spring.temporal.test-server.enabled`, 워크플로 테스트는 `TestWorkflowEnvironment` 의 시간 건너뛰기).
App JWT 테스트는 RSA 키를 테스트 안에서 만들어 서명을 짝 공개키로 검증한다.
