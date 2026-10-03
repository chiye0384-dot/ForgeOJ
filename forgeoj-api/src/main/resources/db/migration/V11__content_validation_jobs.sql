-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Immutable author-owned inputs; no publication, Submission or successful validation seed.
CREATE TABLE content_validation_snapshot (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    draft_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BIGINT UNSIGNED NOT NULL,
    draft_version BIGINT UNSIGNED NOT NULL,
    metadata_text MEDIUMTEXT NOT NULL,
    reference_code MEDIUMTEXT NOT NULL,
    solution_idea MEDIUMTEXT NOT NULL,
    solution_code MEDIUMTEXT NOT NULL,
    reference_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    solution_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    test_dataset_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    java_image_digest VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    time_limit_ms INT UNSIGNED NOT NULL,
    memory_limit_mb INT UNSIGNED NOT NULL,
    output_limit_bytes BIGINT UNSIGNED NOT NULL,
    comparison_rule_version VARCHAR(32) NOT NULL DEFAULT 'trim-trailing-whitespace-v1',
    sandbox_policy_version VARCHAR(32) NOT NULL DEFAULT 'm0-v1',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_content_snapshot_owner(id,owner_id),
    FOREIGN KEY(draft_id) REFERENCES authored_problem_draft(id),
    FOREIGN KEY(owner_id) REFERENCES user_account(id),
    CHECK(draft_version BETWEEN 1 AND 9007199254740991),
    CHECK(JSON_VALID(metadata_text)),
    CHECK(OCTET_LENGTH(reference_code) BETWEEN 1 AND 65536),
    CHECK(OCTET_LENGTH(solution_code) BETWEEN 1 AND 65536),
    CHECK(OCTET_LENGTH(solution_idea) BETWEEN 1 AND 65536),
    CHECK(time_limit_ms BETWEEN 100 AND 30000),
    CHECK(memory_limit_mb BETWEEN 64 AND 2048),
    CHECK(output_limit_bytes BETWEEN 1 AND 16777216)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE content_validation_test_case (
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no INT UNSIGNED NOT NULL,
    input_gzip MEDIUMBLOB NOT NULL,
    expected_output_gzip MEDIUMBLOB NOT NULL,
    input_bytes BIGINT UNSIGNED NOT NULL,
    expected_output_bytes BIGINT UNSIGNED NOT NULL,
    input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_output_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(snapshot_id,sequence_no),
    FOREIGN KEY(snapshot_id) REFERENCES content_validation_snapshot(id),
    CHECK(sequence_no BETWEEN 1 AND 100),
    CHECK(input_bytes<=1048576 AND expected_output_bytes<=1048576)
) ENGINE=InnoDB;
CREATE TABLE content_validation_job (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BIGINT UNSIGNED NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processing_status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    status_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    validation_status VARCHAR(8) NULL,
    reference_result VARCHAR(32) NULL,
    solution_result VARCHAR(32) NULL,
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
    UNIQUE KEY uq_validation_request(owner_id,client_request_id),
    UNIQUE KEY uq_validation_lease(lease_token),
    FOREIGN KEY(snapshot_id,owner_id) REFERENCES content_validation_snapshot(id,owner_id),
    INDEX idx_validation_quota(owner_id,processing_status),
    INDEX idx_validation_recovery(processing_status,lease_expires_at,next_attempt_at),
    CHECK(processing_status IN ('QUEUED','RUNNING','WAITING_RETRY','FINISHED','SYSTEM_ERROR')),
    CHECK(max_attempts BETWEEN 1 AND 10 AND attempt_count<=max_attempts),
    CHECK(validation_status IS NULL OR validation_status IN ('PASSED','FAILED')),
    CHECK(reference_result IS NULL OR reference_result IN ('ACCEPTED','WRONG_ANSWER','COMPILE_ERROR','RUNTIME_ERROR','TIME_LIMIT_EXCEEDED','OUTPUT_LIMIT_EXCEEDED','MEMORY_LIMIT_EXCEEDED','SECURITY_VIOLATION')),
    CHECK(solution_result IS NULL OR solution_result IN ('ACCEPTED','WRONG_ANSWER','COMPILE_ERROR','RUNTIME_ERROR','TIME_LIMIT_EXCEEDED','OUTPUT_LIMIT_EXCEEDED','MEMORY_LIMIT_EXCEEDED','SECURITY_VIOLATION')),
    CHECK((processing_status='FINISHED' AND validation_status IS NOT NULL AND reference_result IS NOT NULL AND solution_result IS NOT NULL
          AND validation_status=IF(reference_result='ACCEPTED' AND solution_result='ACCEPTED','PASSED','FAILED'))
          OR (processing_status<>'FINISHED' AND validation_status IS NULL AND reference_result IS NULL AND solution_result IS NULL)),
    CHECK((processing_status<>'RUNNING' AND lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
          OR (processing_status='RUNNING' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CHECK((processing_status IN ('FINISHED','SYSTEM_ERROR') AND finished_at IS NOT NULL)
          OR (processing_status NOT IN ('FINISHED','SYSTEM_ERROR') AND finished_at IS NULL))
) ENGINE=InnoDB;
CREATE TABLE content_validation_attempt (
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
    UNIQUE KEY uq_validation_attempt(job_id,attempt_no),
    UNIQUE KEY uq_validation_attempt_token(lease_token),
    FOREIGN KEY(job_id) REFERENCES content_validation_job(id),
    CHECK(attempt_status IN ('RUNNING','SUCCEEDED','RETRYABLE_FAILURE','LEASE_EXPIRED','DEAD_LETTERED')),
    CHECK((attempt_status='RUNNING' AND finished_at IS NULL) OR (attempt_status<>'RUNNING' AND finished_at IS NOT NULL))
) ENGINE=InnoDB;
GRANT SELECT,INSERT ON forgeoj.content_validation_snapshot TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.content_validation_test_case TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.content_validation_job TO 'forgeoj_api'@'%';
GRANT SELECT ON forgeoj.content_validation_snapshot TO 'forgeoj_worker'@'%';
GRANT SELECT ON forgeoj.content_validation_test_case TO 'forgeoj_worker'@'%';
GRANT SELECT ON forgeoj.content_validation_job TO 'forgeoj_worker'@'%';
GRANT UPDATE(processing_status,status_version,validation_status,reference_result,solution_result,attempt_count,delivery_sequence,
    lease_owner,lease_token,lease_expires_at,next_attempt_at,last_failure_code,started_at,finished_at)
    ON forgeoj.content_validation_job TO 'forgeoj_worker'@'%';
GRANT SELECT,INSERT,UPDATE ON forgeoj.content_validation_attempt TO 'forgeoj_worker'@'%';
