# 배치 플랫폼(batch-service) 설계

2026-10-17. commerce-service 안의 `@Scheduled` 작업 5개와 모두의 채팅 schedule-service 를 **모두 시스템 콘솔에서 관리하는 배치**로 바꾼다.
원칙은 하나다 — **"언제 돌릴지(트리거)"는 플랫폼이, "무엇을 할지(작업)"는 데이터를 가진 서비스가** 맡는다.

## 1. 목표

- 운영자가 모두 시스템 콘솔 "배치" 탭에서 잡의 주기·사용 여부·타임아웃을 바꾸고, 지금 실행하고, 실행 이력을 본다.
- 잡 코드는 각 서비스에 남는다(등급 산정·포인트 대사는 commerce 의 DB·도메인 코드를 쓴다).
- 실행 이력은 두 겹: `batch_job_run` 표(콘솔 목록·필터·재실행) + OpenSearch `job.run` 이벤트(회차 안의 SQL·Feign·Kafka 로그 전부). `job-` 접두 requestId 가 둘을 잇는다.
- Spring Batch 는 쓰지 않는다. 지금 잡은 수백 건을 한 번 도는 수준이라 청크·재시작이 필요 없다. 나중에 특정 잡 안에서 써도 이 구조는 바뀌지 않는다.

## 2. 구조

```
모두 시스템 콘솔 "배치" 탭 (modu_admin/modu-system)
      │  /batch-service/api-system/**   ROLE_SYSTEM + 내부 토큰 (deploy-service 와 같은 규칙)
      ▼
batch-service (modu_platform — schedule-service 를 옮겨서 키움)
  · batch_job        잡 정의: 서비스·잡 이름·cron·타임존·사용 여부·타임아웃
  · batch_job_run    실행 이력: 한 회차 = 한 행
  · batch_job_change 설정 변경 이력: 누가 언제 무엇을 무엇으로
  · 스케줄러 1개(ShedLock), 수동 실행, 재시작 시 RUNNING 정리
      │  POST http://<service>/api-internal/v1/jobs/{name}/run
      │  X-Internal-Token, X-Request-Id: job-<16자>
      ▼
commerce-service 등 각 서비스
  · GET  /api-internal/v1/jobs            자기 잡 목록(이름·설명) — 콘솔은 이 목록에서 고른다
  · POST /api-internal/v1/jobs/{name}/run 한 회차 실행, 결과 JSON
  · @Scheduled 는 설정 플래그로 끈다 → 전환이 끝나면 코드와 ShedLock 제거
```

### 2.1 각 서비스의 잡 엔드포인트 (1단계, modu_commerce)

- `Job` 인터페이스: `name`, `description`, `run(): Map<String, Any?>`(결과 요약, 예 `{"credited": 3}`).
- `JobRunner`: 이름으로 찾아 `JobMdc.run` 안에서 실행. 잡당 동시 실행 하나(이미 돌면 409). 예외는 잡아 `outcome=failed` 로 돌려준다(`job.run` ERROR 이벤트는 JobMdc 가 남긴다).
- 응답 `JobRun`: `{job, outcome: ok|failed, durationMs, requestId, result?, error?}`. 성공 200, 실패 500(본문은 같은 모양), 모르는 잡 404, 실행 중 409.
- 스케줄러(`@Scheduled`)도 같은 `JobRunner` 를 부른다. 플래그(`modu.tier.scheduler-enabled` 등 기존 것)로 끈다.
- 잡 이름은 지금 `JobMdc` 에 쓰는 것 그대로: `tier-monthly-run`, `purchase-earn-retry`, `push-campaigns`, `point-outbox-relay`, `point-reconcile`.

### 2.2 batch-service (2단계, modu_platform)

- schedule-service(Java, 메신저)를 modu_platform 으로 옮긴다. DB 는 mysql-platform(`modu-platform`), DDL 은 modu_infra `data/mysql/schema/platform.modu-platform.sql`. JPA RW/RO 분리는 deploy-service 와 같다.
- `batch_job`: id, service(`commerce-service` — 주소는 config-repo `modu.services.*` 에서 푼다), job_name, cron, zone(기본 Asia/Seoul), enabled, timeout_seconds, description, created/updated.
  유니크 (service, job_name). **엔드포인트 경로는 저장하지 않는다**(`/api-internal/v1/jobs/{job_name}/run` 고정) — 오타·임의 주소 호출 방지.
- `batch_job_run`: id, batch_job_id, trigger(`CRON`|`MANUAL`), triggered_by(수동이면 직원 이메일), request_id, status(`RUNNING`|`SUCCEEDED`|`FAILED`|`TIMEOUT`|`SKIPPED`), started_at, finished_at, duration_ms, result_json, error, http_status.
  생성 열 `running_job_id`(RUNNING 일 때만 batch_job_id) + 유니크 → "잡당 실행 하나"(deploy-service `running_service` 와 같은 수법). 겹치면 SKIPPED 행을 남긴다.
- `batch_job_change`: batch_job_id, changed_by, changed_at, field, before, after.
- 실행: 동기 HTTP(타임아웃 = timeout_seconds). 2xx → SUCCEEDED + result_json, 5xx/4xx → FAILED + error, 타임아웃 → TIMEOUT. 긴 잡의 비동기(202 + 완료 콜백)는 필요해질 때.
- API `/api-system/batch`: 잡 CRUD(주기 바꾸면 트리거 즉시 재등록 — schedule-service 의 ThreadPoolTaskScheduler 방식 유지), `PATCH enabled`, `POST /{id}/run`(수동), `GET /{id}/runs`, `GET /runs`(전체 이력), `GET /services/{service}/jobs`(서비스에 물어 잡 목록 중계), cron 입력의 다음 실행 시각 미리보기.
- 재시작: RUNNING 행은 FAILED(error="batch-service restarted") 로 닫는다.

### 2.3 콘솔 "배치" 탭 (3단계, modu_admin/modu-system)

- 잡 목록(서비스·이름·주기·다음 실행·마지막 결과·사용 여부 토글), 잡 추가(서비스 고르면 그 서비스의 잡 목록을 불러 고름), 주기 편집(다음 실행 미리보기), "지금 실행".
- 이력 목록(필터: 잡·상태·기간), 행마다 OpenSearch 링크(`attributes.requestId:"job-…"`), 설정 변경 이력.

### 2.4 전환 (4단계)

1. 콘솔에서 commerce 잡 5개를 지금 주기 그대로 등록(enabled=false).
2. 한 잡씩: batch 쪽 enabled=true → config-repo 에서 commerce 플래그 false(`modu.tier.scheduler-enabled` …) → 재기동 → `job.run` 이벤트가 batch 가 보낸 requestId 로 들어오는지 확인.
3. 한 주 뒤 commerce 의 `@Scheduled`·ShedLock 코드 제거.

## 3. 범위 밖 (나중에)

실패 알림(`job.run` failed 감시), 이력 보존 기간, 비동기 잡(202 + 콜백), 재시도 정책, 메신저 서비스 잡 등록.
