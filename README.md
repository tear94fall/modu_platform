# modu_platform

modu 프로젝트(modu_messenger, modu_commerce, modu_admin)가 같이 쓰는 플랫폼 서비스입니다. 2026-09-19 에 modu_chat 의 `backend/` 에서 이력을 유지한 채 분리했습니다.

| 서비스 | 포트 | 역할 |
|---|---|---|
| config-service | 8888 | Spring Cloud Config(native). `config-repo/` 를 서빙하고 `{cipher}` 값을 복호화한다. |
| discovery-service | 8761 | Eureka 서비스 레지스트리. 게이트웨이의 `lb://` 라우팅이 여기 등록 정보를 쓴다. |
| gateway-service | 8000 | Spring Cloud Gateway. JWT 검증(auth-service JWKS), 서비스 라우팅, 백오피스 CORS. |

auth-service 는 회원 데이터의 주인인 modu_messenger 에 남아 있습니다(게이트웨이는 컨테이너 이름 `auth-service` 로 JWKS 를 읽습니다).

## 기동 순서

```bash
# 1) modu_infra: 네트워크 modu-infra, pinpoint-docker, data, monitoring
# 2) 이 스택
cp .env.example .env            # ENCRYPT_KEY, INTERNAL_API_TOKEN 채우기
for s in config-service discovery-service gateway-service; do (cd $s && ./gradlew bootJar); done
docker compose up -d --build
# 3) modu_messenger backend, modu_commerce backend (각 저장소 README)
```

메신저·커머스 서비스는 config-service 에서 설정을 받으므로 이 스택이 먼저 떠 있어야 합니다. 서비스는 설정을 못 받으면 기동에 실패하고 compose 가 재시작합니다(`fail-fast`). discovery 등록에는 기동 후 1분쯤 걸리므로 그 사이 게이트웨이의 503 은 정상입니다.

## config-repo 구조

```
config-repo/
  application.yml        # 모든 제품 공통: 내부 토큰, 백오피스, OAuth(JWKS·클라이언트), Eureka 주소
  messenger/             # 메신저 전용: messenger.yml(kafka·redis·rabbitmq·datasource), messenger-local.yml, storage-service.yml, chat-store-service.yml
  commerce/              # 커머스 전용: commerce-service.yml 등
```

- 메신저 서비스는 `spring.cloud.config.name=messenger,<서비스 이름>` 으로 공통 + 메신저 공통 + 자기 파일을 받습니다.
- 커머스 서비스는 `spring.cloud.config.name=commerce,<서비스 이름>` 같은 식으로 받습니다(현재는 파일이 없어 공통만 내려갑니다).
- 시크릿은 `{cipher}` 로 넣습니다: `curl -X POST http://localhost:8888/encrypt -d '<값>'`.

## 환경변수

`.env.example` 참고. `INTERNAL_API_TOKEN` 은 세 저장소(platform, messenger, commerce)의 `.env` 에 같은 값이어야 서비스 간 내부 호출이 통과합니다.
