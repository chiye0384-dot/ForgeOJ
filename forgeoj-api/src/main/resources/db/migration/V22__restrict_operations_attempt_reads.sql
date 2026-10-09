-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- V20 is append-only. Remove its table-wide read grant before exposing operations.
REVOKE SELECT ON forgeoj.judge_task_attempt FROM 'forgeoj_api'@'%';
REVOKE SELECT ON forgeoj.content_validation_attempt FROM 'forgeoj_api'@'%';
REVOKE SELECT ON forgeoj.self_test_attempt FROM 'forgeoj_api'@'%';
GRANT SELECT(id,judge_task_id,attempt_no,attempt_status,failure_code,started_at,heartbeat_at,lease_expires_at,finished_at)
 ON forgeoj.judge_task_attempt TO 'forgeoj_api'@'%';
GRANT SELECT(id,job_id,attempt_no,attempt_status,failure_code,started_at,heartbeat_at,lease_expires_at,finished_at)
 ON forgeoj.content_validation_attempt TO 'forgeoj_api'@'%';
GRANT SELECT(id,job_id,attempt_no,attempt_status,failure_code,started_at,heartbeat_at,lease_expires_at,finished_at)
 ON forgeoj.self_test_attempt TO 'forgeoj_api'@'%';
