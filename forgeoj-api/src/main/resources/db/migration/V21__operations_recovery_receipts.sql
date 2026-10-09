-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Immutable lifetime recovery receipts. No executable payload or user inputs.
CREATE TABLE operations_recovery_request (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 actor_admin_id BIGINT UNSIGNED NOT NULL,
 client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recovery_scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 task_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 previous_status VARCHAR(16) NOT NULL,
 previous_version BIGINT UNSIGNED NOT NULL,
 previous_attempts INT UNSIGNED NOT NULL,
 previous_max_attempts INT UNSIGNED NOT NULL,
 previous_failure_code VARCHAR(64) NULL,
 previous_finished_at DATETIME(6) NULL,
 resulting_version BIGINT UNSIGNED NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_ops_request(actor_admin_id,client_request_id),
 UNIQUE KEY uq_ops_lifetime(recovery_scope,task_kind,target_id),
 FOREIGN KEY(actor_admin_id) REFERENCES admin_account(id),
 FOREIGN KEY(event_id) REFERENCES outbox_event(id),
 CHECK(recovery_scope IN ('EXECUTION','DELIVERY')),
 CHECK(task_kind IN ('FORMAL','VALIDATE','OUTPUT_PREVIEW','SELF_TEST')),
 CHECK(CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 500)
) ENGINE=InnoDB;
GRANT SELECT,INSERT ON forgeoj.operations_recovery_request TO 'forgeoj_api'@'%';
GRANT UPDATE(max_attempts) ON forgeoj.judge_task TO 'forgeoj_api'@'%';
GRANT UPDATE(processing_status,status_version,max_attempts,delivery_sequence,next_attempt_at,finished_at)
 ON forgeoj.content_validation_job TO 'forgeoj_api'@'%';
GRANT UPDATE(max_attempts,delivery_sequence) ON forgeoj.self_test_job TO 'forgeoj_api'@'%';
-- No grants on verdict, frozen inputs, execution counts, attempt rows or leases.
