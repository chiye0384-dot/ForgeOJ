CREATE TABLE user_account (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_user_account_username UNIQUE (username),
    CONSTRAINT chk_user_account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE problem (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    slug VARCHAR(80) NOT NULL,
    title VARCHAR(200) NOT NULL,
    statement_text TEXT NOT NULL,
    input_description TEXT NOT NULL,
    output_description TEXT NOT NULL,
    public_samples_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    current_judge_version_id BIGINT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_problem_slug UNIQUE (slug),
    CONSTRAINT chk_problem_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE problem_judge_version (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    problem_id BIGINT UNSIGNED NOT NULL,
    version_no INT UNSIGNED NOT NULL,
    time_limit_ms INT UNSIGNED NOT NULL,
    memory_limit_mb INT UNSIGNED NOT NULL,
    output_limit_bytes BIGINT UNSIGNED NOT NULL,
    comparison_rule_version VARCHAR(32) NOT NULL,
    sandbox_policy_version VARCHAR(32) NOT NULL,
    java_image_digest VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    test_dataset_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_judge_version_problem_version UNIQUE (problem_id, version_no),
    CONSTRAINT uq_judge_version_identity UNIQUE (id, problem_id),
    CONSTRAINT fk_judge_version_problem FOREIGN KEY (problem_id) REFERENCES problem (id),
    CONSTRAINT chk_judge_version_limits CHECK (
        time_limit_ms > 0
        AND memory_limit_mb > 0
        AND output_limit_bytes > 0
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE problem
    ADD CONSTRAINT fk_problem_current_judge_version
        FOREIGN KEY (current_judge_version_id, id)
        REFERENCES problem_judge_version (id, problem_id);

CREATE TABLE problem_test_case (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    judge_version_id BIGINT UNSIGNED NOT NULL,
    ordinal INT UNSIGNED NOT NULL,
    input_data_gzip LONGBLOB NOT NULL,
    expected_output_gzip LONGBLOB NOT NULL,
    input_size_bytes BIGINT UNSIGNED NOT NULL,
    output_size_bytes BIGINT UNSIGNED NOT NULL,
    input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    output_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_test_case_version_ordinal UNIQUE (judge_version_id, ordinal),
    CONSTRAINT fk_test_case_judge_version
        FOREIGN KEY (judge_version_id) REFERENCES problem_judge_version (id),
    CONSTRAINT chk_test_case_sizes CHECK (input_size_bytes >= 0 AND output_size_bytes >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE submission (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    problem_id BIGINT UNSIGNED NOT NULL,
    judge_version_id BIGINT UNSIGNED NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    language VARCHAR(16) NOT NULL,
    source_code MEDIUMTEXT NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    time_limit_ms INT UNSIGNED NOT NULL,
    memory_limit_mb INT UNSIGNED NOT NULL,
    output_limit_bytes BIGINT UNSIGNED NOT NULL,
    comparison_rule_version VARCHAR(32) NOT NULL,
    sandbox_policy_version VARCHAR(32) NOT NULL,
    java_image_digest VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    test_dataset_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processing_status VARCHAR(16) NOT NULL,
    verdict VARCHAR(16) NULL,
    status_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    diagnostic_message VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_submission_user_request UNIQUE (user_id, client_request_id),
    CONSTRAINT fk_submission_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_submission_problem FOREIGN KEY (problem_id) REFERENCES problem (id),
    CONSTRAINT fk_submission_judge_version
        FOREIGN KEY (judge_version_id, problem_id)
        REFERENCES problem_judge_version (id, problem_id),
    CONSTRAINT chk_submission_language CHECK (language = 'JAVA_21'),
    CONSTRAINT chk_submission_status CHECK (
        processing_status IN ('QUEUED', 'RUNNING', 'FINISHED', 'SYSTEM_ERROR')
    ),
    CONSTRAINT chk_submission_verdict CHECK (
        verdict IS NULL OR verdict IN ('AC', 'WA', 'CE', 'RE', 'TLE')
    ),
    CONSTRAINT chk_submission_terminal_result CHECK (
        (processing_status = 'FINISHED' AND verdict IS NOT NULL)
        OR (processing_status <> 'FINISHED' AND verdict IS NULL)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE judge_task (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    contract_version INT UNSIGNED NOT NULL,
    task_status VARCHAR(16) NOT NULL,
    status_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_judge_task_submission UNIQUE (submission_id),
    CONSTRAINT fk_judge_task_submission FOREIGN KEY (submission_id) REFERENCES submission (id),
    CONSTRAINT chk_judge_task_type CHECK (task_type = 'JUDGE_SUBMISSION'),
    CONSTRAINT chk_judge_task_contract CHECK (contract_version = 1),
    CONSTRAINT chk_judge_task_status CHECK (
        task_status IN ('QUEUED', 'RUNNING', 'FINISHED', 'SYSTEM_ERROR')
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE outbox_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    contract_version INT UNSIGNED NOT NULL,
    payload JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_outbox_event_aggregate UNIQUE (event_type, aggregate_id),
    INDEX idx_outbox_unpublished (published_at, created_at),
    CONSTRAINT chk_outbox_contract CHECK (contract_version = 1)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

GRANT SELECT ON forgeoj.user_account TO 'forgeoj_api'@'%';
GRANT SELECT ON forgeoj.problem TO 'forgeoj_api'@'%';
GRANT SELECT ON forgeoj.problem_judge_version TO 'forgeoj_api'@'%';
GRANT SELECT, INSERT ON forgeoj.submission TO 'forgeoj_api'@'%';
GRANT SELECT, INSERT ON forgeoj.judge_task TO 'forgeoj_api'@'%';
GRANT SELECT, INSERT, UPDATE ON forgeoj.outbox_event TO 'forgeoj_api'@'%';

GRANT SELECT ON forgeoj.problem_judge_version TO 'forgeoj_worker'@'%';
GRANT SELECT ON forgeoj.problem_test_case TO 'forgeoj_worker'@'%';
GRANT SELECT, UPDATE ON forgeoj.submission TO 'forgeoj_worker'@'%';
GRANT SELECT, UPDATE ON forgeoj.judge_task TO 'forgeoj_worker'@'%';
