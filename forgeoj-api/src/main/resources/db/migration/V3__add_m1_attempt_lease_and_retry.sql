ALTER TABLE submission
    DROP CHECK chk_submission_status,
    DROP CHECK chk_submission_verdict,
    DROP CHECK chk_submission_terminal_result,
    ADD CONSTRAINT chk_submission_status CHECK (
        processing_status IN (
            'QUEUED', 'RUNNING', 'RETRYING', 'FINISHED', 'CANCELLED', 'SYSTEM_ERROR'
        )
    ),
    ADD CONSTRAINT chk_submission_verdict CHECK (
        verdict IS NULL
        OR verdict IN ('AC', 'WA', 'CE', 'RE', 'TLE', 'MLE', 'OLE', 'SECURITY_VIOLATION')
    ),
    ADD CONSTRAINT chk_submission_terminal_result CHECK (
        (processing_status = 'FINISHED' AND verdict IS NOT NULL)
        OR (processing_status <> 'FINISHED' AND verdict IS NULL)
    );

ALTER TABLE judge_task
    ADD COLUMN attempt_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER status_version,
    ADD COLUMN max_attempts INT UNSIGNED NOT NULL DEFAULT 3 AFTER attempt_count,
    ADD COLUMN next_attempt_at DATETIME(6) NULL AFTER max_attempts,
    ADD COLUMN lease_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER next_attempt_at,
    ADD COLUMN lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER lease_owner,
    ADD COLUMN lease_expires_at DATETIME(6) NULL AFTER lease_token,
    ADD COLUMN last_failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER lease_expires_at,
    ADD COLUMN last_failure_message VARCHAR(512) NULL AFTER last_failure_code,
    DROP CHECK chk_judge_task_status,
    ADD CONSTRAINT chk_judge_task_status CHECK (
        task_status IN (
            'QUEUED', 'RUNNING', 'RETRYING', 'FINISHED', 'CANCELLED',
            'SYSTEM_ERROR', 'DEAD_LETTER'
        )
    ),
    ADD CONSTRAINT chk_judge_task_attempt_limit CHECK (
        max_attempts > 0 AND attempt_count <= max_attempts
    ),
    ADD CONSTRAINT chk_judge_task_lease_shape CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    ),
    ADD CONSTRAINT uq_judge_task_lease_token UNIQUE (lease_token),
    ADD INDEX idx_judge_task_recovery (task_status, next_attempt_at, lease_expires_at);

CREATE TABLE judge_task_attempt (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    judge_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_no INT UNSIGNED NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    worker_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_status VARCHAR(24) NOT NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    heartbeat_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_expires_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    failure_message VARCHAR(512) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_judge_task_attempt_number UNIQUE (judge_task_id, attempt_no),
    CONSTRAINT uq_judge_task_attempt_lease UNIQUE (lease_token),
    CONSTRAINT fk_judge_task_attempt_task
        FOREIGN KEY (judge_task_id) REFERENCES judge_task (id),
    CONSTRAINT chk_judge_task_attempt_status CHECK (
        attempt_status IN (
            'RUNNING', 'SUCCEEDED', 'RETRYABLE_FAILURE', 'LEASE_EXPIRED', 'DEAD_LETTERED'
        )
    ),
    CONSTRAINT chk_judge_task_attempt_finish CHECK (
        (attempt_status = 'RUNNING' AND finished_at IS NULL)
        OR (attempt_status <> 'RUNNING' AND finished_at IS NOT NULL)
    ),
    INDEX idx_judge_task_attempt_active (judge_task_id, attempt_status, lease_expires_at)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE outbox_event
    ADD COLUMN sequence_no INT UNSIGNED NOT NULL DEFAULT 0 AFTER contract_version,
    ADD COLUMN publish_attempts INT UNSIGNED NOT NULL DEFAULT 0 AFTER payload,
    ADD COLUMN next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        AFTER publish_attempts,
    ADD COLUMN last_attempt_at DATETIME(6) NULL AFTER next_attempt_at,
    ADD COLUMN last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER last_attempt_at,
    ADD COLUMN failed_at DATETIME(6) NULL AFTER published_at,
    DROP INDEX uq_outbox_event_aggregate,
    ADD CONSTRAINT uq_outbox_event_sequence
        UNIQUE (event_type, aggregate_id, sequence_no),
    ADD INDEX idx_outbox_publishable (published_at, failed_at, next_attempt_at, created_at);

GRANT SELECT, INSERT, UPDATE ON forgeoj.judge_task_attempt TO 'forgeoj_worker'@'%';
GRANT INSERT ON forgeoj.outbox_event TO 'forgeoj_worker'@'%';

