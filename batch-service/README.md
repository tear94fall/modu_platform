# batch-service

모두 시스템 "배치" 탭 백엔드(포트 8910). 잡 정의(어느 서비스의 어느 잡을 어떤 cron 으로 돌리나, 사용 여부, 타임아웃)와 실행 이력을
mysql-platform(`modu-platform`)에 두고, cron 에 맞춰(또는 콘솔의 "지금 실행"으로) 그 서비스의 `POST /api-internal/v1/jobs/{name}/run` 을 불러
결과를 기록한다. 작업 본문은 데이터를 가진 서비스에 있다 — 여기는 "언제 누가 돌렸나"만 맡는다.
설정은 `config-repo/batch-service.yml`(datasource 는 deploy-service 와 같은 계정) + 공통 `application.yml`(`modu.services.*`, 내부 토큰).

설계와 전환 계획은 [`docs/superpowers/specs/2026-10-17-batch-platform-design.md`](../docs/superpowers/specs/2026-10-17-batch-platform-design.md).

## 한 회차의 흐름

1. cron 이 울린다(`CronScheduler`, 파드마다 전부 걸려 있어 ShedLock `batch:job-<id>` 를 먼저 잡은 파드만 진행).
2. `batch_job_run` 에 RUNNING 행을 넣는다(`BatchRunner.start`). 그 잡에 RUNNING 이 이미 있으면 생성 열 `running_job_id` 의 유니크 키가 막아 SKIPPED 행만 남긴다.
3. 서비스를 부른다 — `X-Internal-Token`, `X-Request-Id: <회차 id, job-…>`, 읽기 타임아웃 = 잡의 timeoutSeconds.
4. 응답으로 닫는다: 2xx → SUCCEEDED(`result_json` = 응답 본문), 409 → SKIPPED, 404·그 밖 → FAILED(`error`), 읽기 타임아웃 → TIMEOUT. `batch.run` 이벤트 한 줄.
5. 재시작하면 RUNNING 으로 남은 행은 FAILED(`StaleRunRecovery`)로 닫는다 — 서비스 쪽 결과는 OpenSearch 의 `job.run`(같은 requestId)으로 본다.

## API (`/api-system/batch`, 게이트웨이 `/batch-service/api-system/**`, ROLE_SYSTEM)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/jobs` | 잡 목록(다음 실행 시각·마지막 회차) |
| POST | `/jobs` | 등록 `{service, jobName, cron, zone?, enabled?, timeoutSeconds?, description?}` → 201. 서비스는 `modu.services` 에 있어야 하고, cron 은 6자리 Spring 식 |
| GET / PATCH / DELETE | `/jobs/{id}` | 단건 / 보낸 필드만 수정(트리거 즉시 갱신, 변경 이력) / 삭제(이력은 남는다) |
| POST | `/jobs/{id}/run` | 지금 실행 → 202 `{runId, status}`. `GET /runs/{runId}` 로 폴링 |
| GET | `/jobs/{id}/runs`, `/runs?service&status&limit`, `/runs/{runId}` | 실행 이력 |
| GET | `/jobs/{id}/changes` | 설정 변경 이력(누가 언제 무엇을 무엇으로) |
| GET | `/cron/preview?cron&zone&count` | 다음 실행 시각 미리보기(틀리면 400) |
| GET | `/services`, `/services/{name}/jobs` | 등록 가능한 서비스, 그 서비스가 알려 주는 잡 목록 |

오류는 `{error, message}`. 실행자·변경자는 게이트웨이의 X-Auth-User-Id 를 member-service 로 푼 이름.

## 로그·관찰

- `batch.run` 이벤트(runId·service·job·trigger·outcome(SUCCEEDED|FAILED|TIMEOUT|SKIPPED)·durationMs·httpStatus·error) — OpenSearch `attributes.event:"batch.run"`, 실패만 `and attributes.outcome:"FAILED"`.
- 회차 id 로 양쪽을 한 번에: `attributes.requestId:"job-…"` (batch-service 의 `batch.run` + 서비스의 `job.run`·SQL·Feign·Kafka).
