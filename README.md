# modu_platform

modu 프로젝트(modu_messenger, modu_commerce, modu_admin)가 같이 쓰는 플랫폼 서비스입니다. 2026-09-19 에 modu_chat 의 `backend/` 에서 이력을 유지한 채 분리했습니다.

| 서비스 | 포트 | 역할 |
|---|---|---|
| config-service | 8888 | Spring Cloud Config(native). `config-repo/` 를 서빙하고 `{cipher}` 값을 복호화한다. 관리 콘솔용 조회 `GET /api-admin/config-repo/files`, `/file?path=` 는 비밀값을 가려서 내려주고 게이트웨이(`/config-service/api-admin/**`, 관리자 토큰)로만 연다. |
| gateway-service | 8000 | Spring Cloud Gateway. JWT 검증(auth-service JWKS), 서비스 라우팅(`modu.services.*` 주소), 백오피스 CORS. |

auth-service 는 회원 데이터의 주인인 modu_messenger 에 남아 있습니다(게이트웨이는 컨테이너 이름 `auth-service` 로 JWKS 를 읽습니다).
서비스 레지스트리(Eureka)는 2026-10-03 에 없앴습니다. 아래 "서비스 주소" 참고.

## 기동 순서

```bash
# 1) modu_infra: 네트워크 modu-infra, pinpoint-docker, data, monitoring
# 2) 이 스택
cp .env.example .env            # ENCRYPT_KEY, INTERNAL_API_TOKEN 채우기 (config-service 전용 — 아래 '시크릿 지도')
docker compose pull && docker compose up -d   # CI 가 올린 이미지 (아래 '이미지와 배포')
# 또는 소스에서 직접: docker compose up -d --build  (jar 는 이미지 안에서 만든다, 로컬 ./gradlew bootJar 불필요)
# 3) modu_messenger backend, modu_commerce backend (각 저장소 README)
```

메신저·커머스 서비스는 config-service 에서 설정을 받으므로 이 스택이 먼저 떠 있어야 합니다. 서비스는 설정을 못 받으면 기동에 실패하고 compose 가 재시작합니다(`fail-fast`). 게이트웨이는 뒤 서비스가 아직 안 떠 있어도 자기 readiness 는 UP 이고, 그 라우트만 연결 실패(5xx)로 답하다가 서비스가 뜨면 바로 통합니다(등록 대기 같은 건 없습니다).

## 이미지와 배포

이미지는 GitHub Actions(`.github/workflows/images.yml`)가 만들어 GHCR 에 올립니다. Dockerfile 은 멀티스테이지라 소스에서 jar 까지 이미지 안에서 만들고, 로컬 `--build` 도 같은 파일을 씁니다(config-repo 는 이미지에 들어가지 않고 런타임 볼륨으로 마운트).

| 이미지 | 태그 |
|---|---|
| `ghcr.io/tear94fall/modu-platform/config-service` | develop 푸시 → `develop-<sha7>`, `develop` / master 푸시 → `master-<sha7>`, `latest` |
| `ghcr.io/tear94fall/modu-platform/gateway-service` | 위와 같음 |

- PR(develop·master 대상)은 바뀐 서비스만 테스트 + 빌드하고 푸시하지 않습니다. develop/master 푸시는 테스트 + 빌드 + 푸시. `config-repo/` 만 바뀌면 빌드하지 않습니다.
- `docker compose pull && docker compose up -d` 는 `IMAGE_TAG`(기본 `develop`) 태그를 받습니다. 특정 커밋으로 돌리려면 `IMAGE_TAG=develop-abc1234 docker compose up -d`.
- `docker compose up -d --build` 는 로컬에서 같은 Dockerfile 로 빌드합니다(테스트는 건너뜀, Gradle 캐시는 BuildKit 캐시 마운트).
- GHCR 패키지는 첫 푸시 때 **private** 으로 생깁니다. 저장소는 public 이므로 GitHub UI(프로필 → Packages → 패키지 → Package settings → Change visibility)에서 한 번 public 으로 바꿔야 `docker compose pull` 이 로그인 없이 됩니다.

## 서비스 주소

서비스끼리(Feign)·게이트웨이가 서비스를 부르는 주소는 한 곳, `config-repo/application.yml` 의 `modu.services.*` 입니다.

```yaml
modu:
  services:
    member-service: http://member-service:8080
    chat-service: http://chat-service:9090
    # ... 서비스마다 http://<이름>:<포트>
```

- 이름을 DNS 로 풉니다. docker compose 에선 컨테이너 이름(`container_name`)이, 쿠버네티스에선 같은 이름의 `Service` 가 그 이름이어야 하고 포트도 같아야 합니다. 서비스 레지스트리·클라이언트 로드밸런서는 없습니다(k8s 에선 Service 가 분산합니다).
- 게이트웨이 라우트의 `uri` 는 전부 `${modu.services.<이름>}` 입니다. ws-service 라우트도 같은 http 주소를 쓰고, `Upgrade: websocket` 요청은 게이트웨이가 ws 로 바꿔 프록시합니다. 시스템 콘솔 'API 문서' 목록도 라우트 uri 를 이 표에서 되찾아 만듭니다.
- IDE 로 서비스를 띄울 땐 `messenger/messenger-local.yml`(local 프로필)이 같은 키를 `http://localhost:<포트>` 로 덮어씁니다.
- 서비스를 새로 만들면 여기 한 줄 추가하고, 각 서비스의 `@FeignClient(url = "\${modu.services.<이름>}")` 와 게이트웨이 라우트가 그 키를 씁니다.
- 게이트웨이는 상태가 없어서(JWKS 캐시는 인스턴스별) 2개 이상 복제해도 됩니다. compose 에선 `container_name`·호스트 포트 때문에 1개지만, k8s 에선 replicas 를 올리면 됩니다.

## config-repo 구조

```
config-repo/
  application.yml        # 모든 제품 공통: 내부 토큰, 백오피스, OAuth(JWKS·클라이언트), 서비스 주소(modu.services.*)
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
