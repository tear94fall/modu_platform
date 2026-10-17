-- 운영 DDL(modu_infra data/mysql/schema/changes/2026-10-17-batch-job.sql)의 "잡당 RUNNING 하나" 보증을 H2 에도 그대로 둔다.
-- Hibernate(ddl-auto update)가 엔티티로 표를 만든 뒤에 돈다(spring.jpa.defer-datasource-initialization). running_job_id 를 생성 열로 바꾸고 유니크 키를 건다.
ALTER TABLE batch_job_run DROP CONSTRAINT IF EXISTS uk_batch_job_run_running;
ALTER TABLE batch_job_run DROP COLUMN IF EXISTS running_job_id;
ALTER TABLE batch_job_run ADD COLUMN running_job_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'RUNNING' THEN batch_job_id END);
ALTER TABLE batch_job_run ADD CONSTRAINT uk_batch_job_run_running UNIQUE (running_job_id);
