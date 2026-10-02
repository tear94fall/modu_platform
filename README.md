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
cp .env.example .env            # ENCRYPT_KEY, INTERNAL_API_TOKEN 채우기
for s in config-service gateway-service; do (cd $s && ./gradlew bootJar); done
docker compose up -d --build
# 3) modu_messenger backend, modu_commerce backend (각 저장소 README)
```

메신저·커머스 서비스는 config-service 에서 설정을 받으므로 이 스택이 먼저 떠 있어야 합니다. 서비스는 설정을 못 받으면 기동에 실패하고 compose 가 재시작합니다(`fail-fast`). 게이트웨이는 뒤 서비스가 아직 안 떠 있어도 자기 readiness 는 UP 이고, 그 라우트만 연결 실패(5xx)로 답하다가 서비스가 뜨면 바로 통합니다(등록 대기 같은 건 없습니다).

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
- 시크릿은 `{cipher}` 로 넣습니다: `curl -X POST http://localhost:8888/encrypt -d '<값>'`.

## 환경변수

`.env.example` 참고. `INTERNAL_API_TOKEN` 은 세 저장소(platform, messenger, commerce)의 `.env` 에 같은 값이어야 서비스 간 내부 호출이 통과합니다.
