CREATE TABLE user_judge_quota_lock (
    user_id BIGINT UNSIGNED NOT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_judge_quota_lock_user
        FOREIGN KEY (user_id) REFERENCES user_account (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO user_judge_quota_lock (user_id)
SELECT id FROM user_account;

CREATE INDEX idx_submission_user_processing_status
    ON submission (user_id, processing_status);

-- Waiting retries retain their user's running slot without an active execution lease.
-- The public Submission stays RUNNING; no extra queued slot is consumed.
ALTER TABLE judge_task
    DROP CHECK chk_judge_task_status,
    ADD CONSTRAINT chk_judge_task_status CHECK (
        task_status IN (
            'QUEUED', 'RUNNING', 'RETRYING', 'WAITING_RETRY', 'FINISHED',
            'CANCELLED', 'SYSTEM_ERROR', 'DEAD_LETTER'
        )
    );

GRANT SELECT, INSERT ON forgeoj.user_judge_quota_lock TO 'forgeoj_api'@'%';
GRANT UPDATE (lock_version) ON forgeoj.user_judge_quota_lock TO 'forgeoj_api'@'%';

GRANT SELECT ON forgeoj.user_judge_quota_lock TO 'forgeoj_worker'@'%';
GRANT UPDATE (lock_version) ON forgeoj.user_judge_quota_lock TO 'forgeoj_worker'@'%';

GRANT UPDATE (task_status, status_version, finished_at, next_attempt_at)
    ON forgeoj.judge_task TO 'forgeoj_api'@'%';
GRANT UPDATE (processing_status, status_version, finished_at)
    ON forgeoj.submission TO 'forgeoj_api'@'%';
