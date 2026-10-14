# modu_platform

modu 프로젝트(modu_messenger, modu_commerce, modu_admin)가 같이 쓰는 플랫폼 서비스입니다. 2026-09-19 에 modu_chat 의 `backend/` 에서 이력을 유지한 채 분리했습니다.

| 서비스 | 포트 | 역할 |
|---|---|---|
| config-service | 8888 | Spring Cloud Config(native). `config-repo/` 를 서빙하고 `{cipher}` 값을 복호화한다. 관리 콘솔용 조회 `GET /api-admin/config-repo/files`, `/file?path=` 는 비밀값을 가려서 내려주고 게이트웨이(`/config-service/api-admin/**`, 관리자 토큰)로만 연다. |
| gateway-service | 8000 | Spring Cloud Gateway. JWT 검증(auth-service JWKS), 서비스 라우팅(`modu.services.*` 주소), 백오피스 CORS. |
| deploy-service | 8900 | 모두 시스템 "배포" 탭 백엔드 — GHCR 태그 조회, modu_infra `kustomization.yaml` 태그 커밋, Argo CD Sync(그 Deployment 만), 롤아웃 진행률. GitHub 인증은 **GitHub App 설치 토큰**(1시간, modu_infra 에만 설치, Contents: Read and write. `deploy.github.app.*`. 레거시 PAT `deploy.github.token` 은 임시 fallback) — [deploy-service/README.md](deploy-service/README.md). GHCR 태그는 Packages REST API 가 아니라 **레지스트리 API(`ghcr.io/v2/...`)로 익명** 조회한다(그 API 는 공개 패키지라도 인증을 요구하고 App·fine-grained 토큰으로는 못 쓴다). `/deploy-service/api-system/**`(ROLE_SYSTEM, 내부 토큰)로만 연다. 설정은 `config-repo/deploy-service.yml`(`deploy.*`, `spring.datasource.master/replica.*`). 배포 이력은 DB `modu-platform` 표 `deployment` — JPA RW/RO 분리: master `mysql-platform`(계정 `platform`)은 쓰기와 진행률 조회(`get`/`update`, 재시작 복구), replica `mysql-platform-replica`(읽기 전용 계정 `platform_ro`)는 이력 목록·서비스 표(`list`/`latest`/`latestSucceeded`/`size`). 콘솔이 배포 직후 2초마다 진행률을 폴링하므로 복제 지연이 없게 진행률은 master 에서만 읽는다. DDL 은 modu_infra `data/mysql/schema/platform.modu-platform.sql`(앱은 master 만 `ddl-auto: validate`, replica 는 검사 안 함). "서비스당 배포 하나"는 잠금이 아니라 `deployment` 표의 생성 열 `running_service`(RUNNING 일 때만 서비스 이름)와 유니크 키 `uk_deployment_running_service` 가 지킨다 — 두 번째 시작 요청은 INSERT 가 거절돼 409 `deploy_in_progress`, 배포가 끝나면 값이 저절로 NULL 이 된다. 인프라 커밋 단계도 잠그지 않고 GitHub contents API 의 파일 sha 를 낙관적 잠금으로 써서 충돌하면 다시 읽어 최대 5번 시도한다. 재시작하면 RUNNING 이던 기록은 지금 k8s 를 보고 닫는다(실행 중 이미지 태그가 기록과 같으면 SUCCEEDED, 아니면 FAILED). readiness 는 `readinessState, masterDb`(master 만 — 레플리카가 늦거나 죽어도 빠지지 않는다). |

auth-service 는 회원 데이터의 주인인 modu_messenger 에 남아 있습니다(게이트웨이는 컨테이너 이름 `auth-service` 로 JWKS 를 읽습니다).
서비스 레지스트리(Eureka)는 2026-10-03 에 없앴습니다. 아래 "서비스 주소" 참고.

## 기동 순서

```bash
# dev 는 전부 k8s(modu_infra k8s/). config-service·gateway-service 도 거기서 뜬다.
cp .env.example .env            # ENCRYPT_KEY, INTERNAL_API_TOKEN — k8s Secret config-service 의 원본(아래 '시크릿 지도')
cd ../modu_infra/k8s && kubectl -n modu create secret generic config-service --from-literal=ENCRYPT_KEY=… --from-literal=INTERNAL_API_TOKEN=…
kubectl apply -k ~/workspace/modu_platform   # config-repo → ConfigMap 3개(루트 kustomization.yaml). 평소엔 Argo CD Application modu-config-repo 가 Sync 한다
```

메신저·커머스 서비스는 config-service 에서 설정을 받으므로 이 스택이 먼저 떠 있어야 합니다. 서비스는 설정을 못 받으면 기동에 실패하고 compose 가 재시작합니다(`fail-fast`). 게이트웨이는 뒤 서비스가 아직 안 떠 있어도 자기 readiness 는 UP 이고, 그 라우트만 연결 실패(5xx)로 답하다가 서비스가 뜨면 바로 통합니다(등록 대기 같은 건 없습니다).

## 이미지와 배포

이미지는 GitHub Actions(`.github/workflows/images.yml`)가 만들어 GHCR 에 올립니다. Dockerfile 은 멀티스테이지라 소스에서 jar 까지 이미지 안에서 만들고, 로컬 `--build` 도 같은 파일을 씁니다(config-repo 는 이미지에 들어가지 않고 런타임 볼륨으로 마운트).

| 이미지 | 태그 |
|---|---|
| `ghcr.io/tear94fall/modu-platform/config-service` | develop 푸시 → `develop-<sha7>`, `develop` / master 푸시 → `master-<sha7>`, `latest` |
| `ghcr.io/tear94fall/modu-platform/gateway-service` | 위와 같음 |
| `ghcr.io/tear94fall/modu-platform/deploy-service` | 위와 같음 |

- PR(develop·master 대상)은 바뀐 서비스만 테스트 + 빌드하고 푸시하지 않습니다. develop/master 푸시는 테스트 + 빌드 + 푸시. `config-repo/` 만 바뀌면 빌드하지 않습니다.
- 배포: dev 는 **k8s**(modu_infra `k8s/`, 네임스페이스 `modu`)에서 돕니다. CI 가 GHCR 에 올린 태그를 `modu_infra/k8s/overlays/dev/kustomization.yaml` 의 `images[].newTag` 에 적어 main 에 머지하고 **Argo CD**(http://localhost:8090, Application `modu-dev`)에서 Sync 하면 그 Deployment 만 롤링됩니다(급할 땐 `kubectl apply -k overlays/dev` 도 되지만 Argo 가 OutOfSync 로 표시)(빠르게는 `kubectl -n modu set image deploy/<svc> <svc>=<이미지>:<태그>`). 로컬에서 빌드한 이미지를 쓰려면 `docker build -t <이미지>:local <디렉터리>` → `docker save <이미지>:local | docker exec -i desktop-control-plane ctr -n k8s.io images import -` 뒤 태그를 `local` 로 적습니다(Dockerfile 은 CI 와 같은 파일). GHCR 패키지는 저장소가 public 이라 처음 푸시 때부터 public 으로 생깁니다(로그인 없이 pull).

## 서비스 주소

서비스끼리(Feign)·게이트웨이가 서비스를 부르는 주소는 한 곳, `config-repo/application.yml` 의 `modu.services.*` 입니다.

```yaml
modu:
  services:
    member-service: http://member-service:8080
    chat-service: http://chat-service:9090
    # ... 서비스마다 http://<이름>:<포트>
```

- 이름을 DNS 로 풉니다. 쿠버네티스의 같은 이름 `Service` 가 그 이름이고 포트도 같아야 합니다(compose 시절엔 컨테이너 이름이었습니다). 서비스 레지스트리·클라이언트 로드밸런서는 없습니다(k8s 에선 Service 가 분산합니다).
- 게이트웨이 라우트의 `uri` 는 전부 `${modu.services.<이름>}` 입니다. ws-service 라우트도 같은 http 주소를 쓰고, `Upgrade: websocket` 요청은 게이트웨이가 ws 로 바꿔 프록시합니다. 시스템 콘솔 'API 문서' 목록도 라우트 uri 를 이 표에서 되찾아 만듭니다.
- IDE 로 서비스를 띄울 땐 `messenger/messenger-local.yml`(local 프로필)이 같은 키를 `http://localhost:<포트>` 로 덮어씁니다.
- 서비스를 새로 만들면 여기 한 줄 추가하고, 각 서비스의 `@FeignClient(url = "\${modu.services.<이름>}")` 와 게이트웨이 라우트가 그 키를 씁니다.
- 게이트웨이는 상태가 없어서(JWKS 캐시는 인스턴스별) 2개 이상 복제해도 됩니다. compose 에선 `container_name`·호스트 포트 때문에 1개지만, k8s 에선 replicas 를 올리면 됩니다.

## config-repo 구조

```
config-repo/
  application.yml        # 모든 제품 공통: 내부 토큰, 백오피스, OAuth(JWKS·클라이언트), 서비스 주소(modu.services.*)
  deploy-service.yml     # deploy-service 전용(루트에 둔다 — 제품이 아니라 플랫폼 서비스): GitHub App 비밀키·Argo CD 토큰({cipher}), 배포 대상 서비스 목록
  messenger/             # 메신저 전용: messenger.yml(kafka·redis·rabbitmq·datasource), messenger-local.yml, storage-service.yml, chat-store-service.yml
  commerce/              # 커머스 전용: commerce-service.yml 등
```

- 메신저 서비스는 `spring.cloud.config.name=messenger,<서비스 이름>` 으로 공통 + 메신저 공통 + 자기 파일을 받습니다.
- 커머스 서비스는 `spring.cloud.config.name=commerce,<서비스 이름>` 같은 식으로 받습니다(현재는 파일이 없어 공통만 내려갑니다).
- 시크릿은 `{cipher}` 로 넣습니다. 절차는 아래 '시크릿 지도'.

## 환경변수

`.env.example` 참고. 이 `.env` 는 **config-service 만** 읽습니다(`ENCRYPT_KEY`, `INTERNAL_API_TOKEN`). 다른 서비스·게이트웨이·messenger·commerce 스택에는 시크릿 환경변수가 없습니다 — 전부 config-service 가 내려줍니다.

## 시크릿 지도

애플리케이션 시크릿은 **config-repo 에 `{cipher}` 로** 두고, 서비스는 뜰 때 config-service 에서 평문으로 받습니다. 나중에 HashiCorp Vault 로 옮길 때는 config-service 의 백엔드를 composite(git + vault)로 바꾸면 되고, 서비스 코드는 바뀌지 않습니다.

| 시크릿 | 어디 | 누가 읽나 |
|---|---|---|
| 서비스 간 내부 토큰 `modu.internal-api.token` | `config-repo/application.yml` | 모든 서비스, 게이트웨이(X-Internal-Token 부착) |
| MySQL master/replica 비밀번호, RSA 서명 키, MinIO 키, Mongo URI | `messenger/messenger.yml`, `messenger/storage-service.yml`, `messenger/chat-store-service.yml`, `commerce/commerce-service.yml` | 각 서비스 |
| Firebase 서비스 계정 키(JSON → base64) | `messenger/push-service.yml`, `commerce/commerce-service.yml` | push-service, commerce-service |
| GitHub App 비밀키 `deploy.github.app.private-key`(PKCS#8 PEM. 짝 `deploy.github.app.id` = App ID, `installation-id` 는 선택 — 둘은 비밀이 아니다. 권한은 modu_infra 의 Contents: Read and write 하나뿐이고 설치도 modu_infra 뿐. 설치 토큰은 1시간) — 레거시 `deploy.github.token`(classic PAT)은 **임시 fallback**, 앱으로 옮긴 뒤 지운다. Argo CD API 토큰 `deploy.argocd.token`(applications get/sync) | `config-repo/deploy-service.yml` | deploy-service ([자세히](deploy-service/README.md)) |
| 배포 이력 DB 비밀번호 — master `spring.datasource.master.password`(mysql-platform 의 `platform` 계정, k8s Secret infra `PLATFORM_DB_USER_PASSWORD` 와 같은 값), replica `spring.datasource.replica.password`(mysql-platform-replica 의 읽기 전용 `platform_ro` 계정) | `config-repo/deploy-service.yml` | deploy-service |
| (없음) Google 로그인 | — | ID 토큰의 aud 만 검사하므로 client secret 이 필요 없다. `modu.oauth.google.audiences`(공개 값)는 `application.yml` |
| **부트스트랩** `ENCRYPT_KEY`(복호화 키), `INTERNAL_API_TOKEN`(config-service 의 /api-admin·/encrypt·/decrypt 보호) | `modu_platform/.env` → config-service 환경변수 | config-service 자신. config 에서 받을 수 없는 유일한 둘. k8s 에선 Secret 하나(`config-service`) |

config-repo 의 `modu.internal-api.token` 과 `.env` 의 `INTERNAL_API_TOKEN` 은 **같은 값**이어야 합니다(게이트웨이가 config 에서 받은 토큰으로 config-service 의 /api-admin 을 부릅니다).

**새 시크릿 넣기 / 바꾸기**
```bash
# 1) 암호화 — /encrypt 는 X-Internal-Token(.env 의 INTERNAL_API_TOKEN)이 있어야 열린다
printf '%s' '<값>' | curl -s -X POST http://localhost:8888/encrypt -H 'X-Internal-Token: <token>' -H 'Content-Type: text/plain' --data-binary @-
# JSON 처럼 '{' 로 시작하는 값은 반드시 base64 로 감싼 뒤 암호화한다('{...}' 를 키 접두사로 해석해 평문이 그대로 흐른다)
base64 -i key.json | tr -d '\n' | curl -s -X POST http://localhost:8888/encrypt -H 'X-Internal-Token: <token>' -H 'Content-Type: text/plain' --data-binary @-
# 2) config-repo 의 해당 파일에 '{cipher}<출력>' 으로 넣고 PR
# 3) config-service 는 native 저장소를 요청마다 읽으므로 재시작 불필요. 값을 받는 서비스를 재시작(또는 /actuator/refresh)
```
확인: `curl -s http://localhost:8888/messenger,<서비스>/default` 에 키가 보이고 값이 `invalid` 로 시작하지 않으면 복호화된 것입니다(값은 평문이니 출력에 주의).

**주의**: config-service 는 사실상 금고입니다. 내부 네트워크에만 노출하고(k8s 에선 NetworkPolicy 로 앱 파드만 허용), `ENCRYPT_KEY` 가 새면 config-repo 의 `{cipher}` 를 전부 다시 암호화해야 합니다.
