# batch-service 구현 계획 (배치 플랫폼 2단계)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 모두 시스템 콘솔이 쓰는 배치 플랫폼 서비스 — 잡 정의(주기·사용 여부·타임아웃)와 실행 이력을 mysql-platform 에 두고, cron 에 맞춰(또는 수동으로) 각 서비스의 `POST /api-internal/v1/jobs/{name}/run` 을 불러 결과를 기록한다.

**Architecture:** deploy-service 와 같은 틀(Kotlin, Spring Boot 4.0.8, JPA RW/RO 분리, `/api-system/**` + InternalTokenFilter, config-repo 설정, `running_*` 생성 열 유니크 키). 스케줄은 `ThreadPoolTaskScheduler` + `CronTrigger` 를 DB 의 `batch_job` 으로 동적으로 걸고(schedule-service 방식), 회차 본문은 ShedLock(`batch:job-<id>`)으로 파드 간 중복을 막는다. 회차 하나 = `batch_job_run` 한 행, requestId(`job-` 접두)가 행의 id 이자 서비스에 보내는 `X-Request-Id`.

**Tech Stack:** Kotlin 2.4.21 / Spring Boot 4.0.8 / Spring Cloud 2025.1.3 / spring-data-jpa + MySQL(H2 테스트) / ShedLock 7.10.1 / RestClient / springdoc 3.0.3 / logstash-logback-encoder 8.1.

**Spec:** `docs/superpowers/specs/2026-10-17-batch-platform-design.md` §2.2

## Global Constraints

- 포트 **8910**. `modu.services.batch-service: http://batch-service:8910`(config-repo application.yml). 게이트웨이 라우트 `batch-service-system`: `/batch-service/api-system/**`, `AuthorizationHeaderFilter=ROLE_SYSTEM,modu-admin` + `X-Internal-Token`.
- 설정은 `config-repo/batch-service.yml`(`spring.datasource.master/replica.*` 는 deploy-service 와 같은 계정·{cipher}, `batch.*`). 저장소 안 application.yml 에 datasource 기본값을 두지 않는다.
- DDL 은 DBA 소유: modu_infra `data/mysql/schema/changes/2026-10-17-batch-job.sql` + 기준선 `platform.modu-platform.sql` 갱신. 앱은 master 만 `ddl-auto: validate`.
- 서비스 호출 경로는 고정 `/api-internal/v1/jobs/{jobName}/run`(저장하지 않는다). 주소는 `modu.services.<service>`.
- 오류 응답 `{error, message}`(ApiErrors 그대로). 한국어 메시지.
- 로그: `http.access`(RequestContextFilter), 회차마다 `batch.run` 이벤트 한 줄(job·service·runId·status·durationMs·httpStatus·error). MDC requestId = run id.

## Review Focus

- 같은 잡의 cron 회차가 앞 회차가 아직 RUNNING 일 때 울리면 SKIPPED 행 하나를 남기고 서비스를 부르지 않는다(유니크 키 `uk_batch_job_run_running` + 사전 확인). Task 3 테스트.
- 서비스가 409(이미 실행 중) 를 돌려주면 SKIPPED, 404 면 FAILED(`unknown job on service`), 5xx 면 FAILED(본문의 error), 읽기 타임아웃이면 TIMEOUT. Task 3 테스트.
- enabled=false 인 잡은 cron 을 걸지 않고, PATCH 로 enabled·cron·zone 이 바뀌면 트리거를 즉시 다시 건다(재시작 없이). Task 4 테스트.
- 잘못된 cron(`"* * *"`)·모르는 zone·모르는 service 는 400 이고 저장되지 않는다. Task 5 테스트.
- 재시작 때 RUNNING 행은 FAILED(`batch-service restarted`)로 닫힌다. Task 4 테스트.

---

### Task 1: 스캐폴드 + 공통 설정 (컨텍스트가 뜬다)

**Files:** `batch-service/{build.gradle.kts, settings.gradle.kts, gradlew*, gradle/wrapper/*, Dockerfile, .dockerignore, .gitignore, README.md}`,
`src/main/resources/{application.yml, logback-spring.xml}`, `src/test/resources/{config/application.yml, logback-test.xml, schema-shedlock.sql}`,
`src/main/kotlin/com/example/batchservice/BatchServiceApplication.kt`,
`config/{RwJpaConfig, RoJpaConfig, RwRepository, RoRepository, MasterDbHealthConfig, OpenApiConfig, SchedulerLockConfig, BatchProperties}.kt`,
`api/{InternalTokenFilter, ApiErrors}.kt`, `logging/RequestContextFilter.kt`.
**Test:** `BatchServiceApplicationTests`(probe UP, readiness 에 masterDb, api-docs 열림, `/api-system/batch/jobs` 토큰 없이 401), `api/InternalTokenFilterTest`(deploy 와 같음).
- deploy-service 의 파일을 패키지만 바꿔 복사. `ENTITY_PACKAGE = "com.example.batchservice.job"`, rw/ro 패키지 `job.rw`/`job.ro`.
- `BatchProperties(prefix="batch")`: `run.connectTimeoutSeconds=5`, `run.defaultTimeoutSeconds=600`, `run.maxTimeoutSeconds=3600`, `store.capacity=500`, `scheduler.poolSize=5`.
- `ModuServices(prefix="modu")`: `services: Map<String,String>`, `internalApi.token`.
- `SchedulerLockConfig`: `JdbcTemplateLockProvider(rwHikariDataSource).usingDbTime()` + `LockingTaskExecutor`. 테스트 H2 는 `spring.sql.init.schema-locations: classpath:schema-shedlock.sql`.
- [ ] 커밋 `Feat: "batch-service 스캐폴드 — deploy-service 와 같은 틀(JPA RW/RO, 내부 토큰, 설정 서버, ShedLock)"`

### Task 2: 도메인 + 저장소

**Files:** `job/{BatchJob.kt(레코드·enum), BatchJobEntity.kt, BatchJobRunEntity.kt, BatchJobChangeEntity.kt, BatchJobStore.kt(인터페이스 + InMemoryBatchJobStore), JpaBatchJobStore.kt}`, `job/rw/{BatchJobRwRepository, BatchJobRunRwRepository, BatchJobChangeRwRepository}.kt`, `job/ro/{BatchJobRoRepository, BatchJobRunRoRepository, BatchJobChangeRoRepository}.kt`.
**Test:** `job/JpaBatchJobStoreTest`(@DataJpaTest H2, RW/RO import): 잡 저장·조회·수정·삭제, run `startIfNoneRunning` 두 번째 false, run 종료 update, 목록 최신순·limit, 변경 이력 기록.

```kotlin
enum class RunTrigger { CRON, MANUAL }
enum class RunStatus { RUNNING, SUCCEEDED, FAILED, TIMEOUT, SKIPPED }
data class BatchJob(val id: Long?, val service: String, val jobName: String, val description: String?, val cron: String,
    val zone: String = "Asia/Seoul", val enabled: Boolean = false, val timeoutSeconds: Int = 600, val createdAt: Instant?, val updatedAt: Instant?)
data class BatchJobRun(val id: String, val batchJobId: Long, val service: String, val jobName: String, val trigger: RunTrigger,
    val triggeredBy: String?, val status: RunStatus, val startedAt: Instant, val finishedAt: Instant? = null, val durationMs: Long? = null,
    val httpStatus: Int? = null, val result: Map<String, Any?>? = null, val error: String? = null)
data class BatchJobChange(val id: Long?, val batchJobId: Long, val changedBy: String, val changedAt: Instant, val field: String, val before: String?, val after: String?)
interface BatchJobStore {
    fun saveJob(job: BatchJob): BatchJob; fun job(id: Long): BatchJob?; fun jobs(): List<BatchJob>; fun deleteJob(id: Long)
    fun addChange(change: BatchJobChange)
    fun changes(jobId: Long, limit: Int): List<BatchJobChange>
    fun startRunIfNoneRunning(run: BatchJobRun): Boolean   // RUNNING 행 INSERT, 유니크 키 위반이면 false
    fun addRun(run: BatchJobRun)                            // SKIPPED 같은 완결 행
    fun updateRun(id: String, update: (BatchJobRun) -> BatchJobRun): BatchJobRun?
    fun run(id: String): BatchJobRun?                       // master
    fun runs(jobId: Long?, service: String?, status: RunStatus?, limit: Int): List<BatchJobRun>   // replica, 최신순
    fun lastRuns(): Map<Long, BatchJobRun>                   // 잡별 마지막 회차(replica)
    fun runningRuns(): List<BatchJobRun>                     // master, 재시작 복구
}
```
- [ ] 커밋 `Feat: "batch_job·batch_job_run·batch_job_change — JPA RW/RO 저장소, 잡당 RUNNING 하나는 생성 열 유니크 키"`

### Task 3: JobCaller + BatchRunner

**Files:** `run/JobCaller.kt`(인터페이스 `call(baseUrl, jobName, runId, timeout): JobCallResult(status: Int?, body: String?, timedOut: Boolean)` + `RestClientJobCaller`), `run/BatchRunner.kt`.
**Test:** `run/BatchRunnerTest`(InMemory store + 가짜 caller): 200 → SUCCEEDED + result(JSON 본문) + durationMs; 500 `{"outcome":"failed","error":"x"}` → FAILED error=x; 409 → SKIPPED; 404 → FAILED; timedOut → TIMEOUT; 이미 RUNNING → SKIPPED 행 + caller 호출 없음; 모르는 service → FAILED(`unknown service`). `run/RestClientJobCallerTest`(MockRestServiceServer): 헤더 X-Internal-Token·X-Request-Id, 경로 `/api-internal/v1/jobs/{name}/run`.
- `BatchRunner.run(job: BatchJob, trigger: RunTrigger, triggeredBy: String?): BatchJobRun` — runId = `"job-" + 16hex`, MDC requestId=runId·job=`<service>/<jobName>`, `batch.run` 이벤트.
- [ ] 커밋 `Feat: "BatchRunner — 회차 하나를 RUNNING 행으로 열고 서비스를 불러 SUCCEEDED/FAILED/TIMEOUT/SKIPPED 로 닫는다"`

### Task 4: CronScheduler + StaleRunRecovery

**Files:** `run/CronScheduler.kt`, `run/StaleRunRecovery.kt`, `run/Crons.kt`(`validate(cron, zone)`, `next(cron, zone, count, from)`).
**Test:** `run/CronsTest`(미리보기·검증), `run/CronSchedulerTest`(가짜 TaskScheduler 로 register/unregister/refresh, enabled=false 는 안 건다, 울리면 LockingTaskExecutor 안에서 runner.run(CRON)), `run/StaleRunRecoveryTest`(InMemory: RUNNING → FAILED).
- `CronScheduler`: `ApplicationRunner` 로 기동 때 `store.jobs().filter{enabled}` 전부 등록. `register(job)`, `unregister(id)`, `refresh(job)`, `nextRunAt(jobId): Instant?`, `runNow(job, by)`(풀에서 비동기 실행, runId 를 돌려주려면 runner 가 먼저 RUNNING 행을 만들도록 `BatchRunner.start(job, trigger, by): BatchJobRun` + `complete(run, job)` 로 나눈다).
- [ ] 커밋 `Feat: "CronScheduler — DB 의 batch_job 으로 cron 트리거를 동적으로 걸고(ShedLock) 재시작 때 RUNNING 회차를 닫는다"`

### Task 5: BatchService + BatchController

**Files:** `api/BatchController.kt`(`/api-system/batch`), `job/BatchService.kt`, `api/BatchDtos.kt`, `gateway/ServiceJobsGateway.kt`(`GET {url}/api-internal/v1/jobs`).
**Test:** `api/BatchControllerTest`(@WebMvcTest + InternalTokenFilter, service mock): 목록·생성 201·잘못된 cron 400·PATCH·DELETE 204·수동 실행 202·runs·changes·cron preview·services·service jobs. `BatchServiceApplicationTests` 에 api-docs 경로 확인 추가.

| 메서드 | 경로 | 응답 |
|---|---|---|
| GET | /jobs | `{jobs:[{…BatchJob, nextRunAt, lastRun}]}` |
| POST | /jobs `{service, jobName, cron, zone?, enabled?, timeoutSeconds?, description?}` | 201 BatchJobView |
| GET/PATCH/DELETE | /jobs/{id} | 200 / 200 / 204 |
| POST | /jobs/{id}/run | 202 `{runId}` |
| GET | /jobs/{id}/runs?limit | `{runs:[…]}` |
| GET | /jobs/{id}/changes | `{changes:[…]}` |
| GET | /runs?service&status&limit, /runs/{id} | |
| GET | /cron/preview?cron&zone&count | `{next:[Instant…]}` 또는 400 |
| GET | /services, /services/{name}/jobs | `{services:[{name,url}]}`, `{jobs:[{name,description}]}` |

- 변경 기록: PATCH 가 바꾼 필드마다 `batch_job_change`(changedBy = X-Auth-User-Id 를 member-service 로 푼 이름, deploy 의 StaffLookup 복사). 생성은 field=`created`, 삭제는 `deleted`.
- [ ] 커밋 `Feat: "/api-system/batch — 잡 CRUD·사용 여부·수동 실행·실행 이력·변경 이력·cron 미리보기·서비스 잡 목록"`

### Task 6: 설정·라우트·CI·문서 (modu_platform)

- `config-repo/batch-service.yml`(datasource master/replica = deploy-service 와 같은 {cipher}, `batch.*`), `config-repo/application.yml` 에 `modu.services.batch-service`, 루트 `kustomization.yaml` files 에 추가(`./k8s-check.sh`), `config-repo/deploy-service.yml` services 에 batch-service.
- `gateway-service/src/main/resources/application.yml` 라우트 `batch-service-system`, `GatewayRoutesTest` 에 케이스, 테스트 설정의 `modu.services.batch-service`.
- `.github/workflows/images.yml` 필터·dispatch 목록에 batch-service. 루트 README 표·시크릿 지도.
- [ ] 커밋 `Feat: "batch-service 설정·게이트웨이 라우트·CI"`

### Task 7: 인프라 + 배포 + 확인 (modu_infra)

- `data/mysql/schema/changes/2026-10-17-batch-job.sql`(batch_job·batch_job_run·batch_job_change·shedlock), 기준선 `platform.modu-platform.sql` 갱신. `kubectl exec mysql-platform-0` 로 적용(레플리카는 복제).
- `k8s/base/platform/batch-service.yaml`(deploy-service 복사: ServiceAccount 불필요, 포트 8910, sync-wave 15), `base/kustomization.yaml` resources, `overlays/dev/kustomization.yaml` images·replicas.
- 로컬 이미지 `ghcr.io/tear94fall/modu-platform/batch-service:local-<MMDD>-<sha>` 빌드 → apply → gateway-service 도 새 라우트로 빌드·롤아웃.
- 확인: 포트포워드로 `POST /api-system/batch/jobs {service: commerce-service, jobName: point-outbox-relay, cron: "0 */2 * * * *", enabled: true}` → 2분 안에 `batch_job_run` SUCCEEDED 행, result 에 commerce 의 JobRun, OpenSearch 에 같은 requestId 로 commerce `job.run` + batch `batch.run`. 확인 뒤 잡은 enabled=false(전환은 4단계).
