-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Ephemeral private payloads; permanent minimal execution/closed-attempt facts.
CREATE TABLE self_test_snapshot (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 owner_id BIGINT UNSIGNED NOT NULL,
 problem_id BIGINT UNSIGNED NOT NULL,
 problem_slug VARCHAR(128) NOT NULL,
 judge_version_id BIGINT UNSIGNED NOT NULL,
 language VARCHAR(16) NOT NULL,
 source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 input_bytes BIGINT UNSIGNED NOT NULL,
 time_limit_ms INT UNSIGNED NOT NULL,
 memory_limit_mb INT UNSIGNED NOT NULL,
 output_limit_bytes BIGINT UNSIGNED NOT NULL,
 java_image_digest VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 comparison_rule_version VARCHAR(32) NOT NULL,
 sandbox_policy_version VARCHAR(32) NOT NULL,
 snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_self_snapshot_owner(id,owner_id),
 FOREIGN KEY(owner_id) REFERENCES user_account(id),
 FOREIGN KEY(problem_id) REFERENCES problem(id),
 FOREIGN KEY(judge_version_id) REFERENCES problem_judge_version(id),
 CHECK(language='JAVA_21'), CHECK(input_bytes<=1048576),
 CHECK(time_limit_ms BETWEEN 100 AND 30000), CHECK(memory_limit_mb BETWEEN 64 AND 2048),
 CHECK(output_limit_bytes BETWEEN 1 AND 1048576)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE self_test_payload (
 snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 source_code MEDIUMTEXT NOT NULL,
 input_text MEDIUMTEXT NOT NULL,
 FOREIGN KEY(snapshot_id) REFERENCES self_test_snapshot(id),
 CHECK(OCTET_LENGTH(source_code) BETWEEN 1 AND 65536),
 CHECK(OCTET_LENGTH(input_text)<=1048576)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE self_test_job (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 owner_id BIGINT UNSIGNED NOT NULL,
 client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 processing_status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
 status_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 execution_result VARCHAR(32) NULL,
 attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
 max_attempts INT UNSIGNED NOT NULL DEFAULT 3,
 delivery_sequence INT UNSIGNED NOT NULL DEFAULT 0,
 lease_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_expires_at DATETIME(6) NULL,
 next_attempt_at DATETIME(6) NULL,
 last_failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 started_at DATETIME(6) NULL,
 finished_at DATETIME(6) NULL,
 expires_at DATETIME(6) NULL,
 UNIQUE KEY uq_self_request(owner_id,client_request_id),
 UNIQUE KEY uq_self_lease(lease_token),
 UNIQUE KEY uq_self_binding(id,snapshot_id),
 FOREIGN KEY(snapshot_id,owner_id) REFERENCES self_test_snapshot(id,owner_id),
 INDEX idx_self_quota(owner_id,processing_status),
 INDEX idx_self_expiry(expires_at),
 INDEX idx_self_recovery(processing_status,lease_expires_at,next_attempt_at),
 CHECK(processing_status IN ('QUEUED','RUNNING','WAITING_RETRY','FINISHED','CANCELLED','SYSTEM_ERROR')),
 CHECK(max_attempts BETWEEN 1 AND 10 AND attempt_count<=max_attempts),
 CHECK(execution_result IS NULL OR execution_result IN ('SUCCESS','COMPILE_ERROR','RUNTIME_ERROR','TIME_LIMIT_EXCEEDED','OUTPUT_LIMIT_EXCEEDED','MEMORY_LIMIT_EXCEEDED','SECURITY_VIOLATION')),
 CHECK((processing_status='FINISHED' AND execution_result IS NOT NULL) OR (processing_status<>'FINISHED' AND execution_result IS NULL)),
 CHECK((processing_status='RUNNING' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL) OR (processing_status<>'RUNNING' AND lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)),
 CHECK((processing_status IN ('FINISHED','CANCELLED','SYSTEM_ERROR') AND finished_at IS NOT NULL AND expires_at IS NOT NULL) OR (processing_status NOT IN ('FINISHED','CANCELLED','SYSTEM_ERROR') AND finished_at IS NULL AND expires_at IS NULL))
) ENGINE=InnoDB;
CREATE TABLE self_test_attempt (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 attempt_no INT UNSIGNED NOT NULL,
 lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 worker_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 attempt_status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
 started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 heartbeat_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 lease_expires_at DATETIME(6) NOT NULL,
 finished_at DATETIME(6) NULL,
 failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 UNIQUE KEY uq_self_attempt(job_id,attempt_no),
 UNIQUE KEY uq_self_attempt_token(lease_token),
 FOREIGN KEY(job_id) REFERENCES self_test_job(id),
 CHECK(attempt_status IN ('RUNNING','SUCCEEDED','RETRYABLE_FAILURE','LEASE_EXPIRED','DEAD_LETTERED')),
 CHECK((attempt_status='RUNNING' AND finished_at IS NULL) OR (attempt_status<>'RUNNING' AND finished_at IS NOT NULL))
) ENGINE=InnoDB;
CREATE TABLE self_test_output (
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 output_gzip MEDIUMBLOB NOT NULL,
 output_bytes BIGINT UNSIGNED NOT NULL,
 output_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY(job_id,snapshot_id) REFERENCES self_test_job(id,snapshot_id),
 CHECK(output_bytes<=1048576)
) ENGINE=InnoDB;
GRANT SELECT,INSERT ON forgeoj.self_test_snapshot TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT,DELETE ON forgeoj.self_test_payload TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.self_test_job TO 'forgeoj_api'@'%';
GRANT UPDATE(processing_status,status_version,finished_at,expires_at,next_attempt_at) ON forgeoj.self_test_job TO 'forgeoj_api'@'%';
GRANT SELECT,DELETE ON forgeoj.self_test_output TO 'forgeoj_api'@'%';
GRANT SELECT ON forgeoj.self_test_snapshot TO 'forgeoj_worker'@'%';
GRANT SELECT ON forgeoj.self_test_payload TO 'forgeoj_worker'@'%';
GRANT SELECT ON forgeoj.self_test_job TO 'forgeoj_worker'@'%';
GRANT UPDATE(processing_status,status_version,execution_result,attempt_count,delivery_sequence,lease_owner,lease_token,lease_expires_at,next_attempt_at,last_failure_code,started_at,finished_at,expires_at) ON forgeoj.self_test_job TO 'forgeoj_worker'@'%';
GRANT SELECT,INSERT,UPDATE ON forgeoj.self_test_attempt TO 'forgeoj_worker'@'%';
GRANT SELECT,INSERT ON forgeoj.self_test_output TO 'forgeoj_worker'@'%';
