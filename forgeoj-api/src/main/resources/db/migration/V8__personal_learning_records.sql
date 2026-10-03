-- Copyright 2026 池也
-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE personal_problem_list (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    owner_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(64) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_personal_list_owner (owner_id, created_at, id),
    FOREIGN KEY (owner_id) REFERENCES user_account(id),
    CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 64),
    CHECK (version BETWEEN 1 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE personal_problem_list_item (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    list_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    problem_id BIGINT UNSIGNED NOT NULL,
    position INT UNSIGNED NOT NULL,
    UNIQUE KEY uq_personal_list_problem(list_id, problem_id),
    UNIQUE KEY uq_personal_list_position(list_id, position),
    FOREIGN KEY(list_id) REFERENCES personal_problem_list(id),
    FOREIGN KEY(problem_id) REFERENCES problem(id),
    CHECK(position > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE official_problem_list (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    title VARCHAR(64) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_official_list_status(status, created_at, id),
    CHECK(status IN ('ACTIVE','ARCHIVED')),
    CHECK(CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 64),
    CHECK(version BETWEEN 1 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE official_problem_list_item (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    list_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    problem_id BIGINT UNSIGNED NOT NULL,
    position INT UNSIGNED NOT NULL,
    UNIQUE KEY uq_official_list_problem(list_id, problem_id),
    UNIQUE KEY uq_official_list_position(list_id, position),
    FOREIGN KEY(list_id) REFERENCES official_problem_list(id),
    FOREIGN KEY(problem_id) REFERENCES problem(id),
    CHECK(position > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE user_code_draft (
    user_id BIGINT UNSIGNED NOT NULL,
    problem_id BIGINT UNSIGNED NOT NULL,
    language VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_code MEDIUMTEXT NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(user_id, problem_id, language),
    FOREIGN KEY(user_id) REFERENCES user_account(id),
    FOREIGN KEY(problem_id) REFERENCES problem(id),
    CHECK(language = 'JAVA_21'),
    CHECK(version BETWEEN 1 AND 9007199254740991),
    CHECK(OCTET_LENGTH(source_code) <= 65536)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_submission_current_ac ON submission(user_id,problem_id,judge_version_id,processing_status,verdict);
CREATE INDEX idx_submission_owner_history ON submission(user_id,created_at,id);
GRANT SELECT,INSERT,DELETE ON forgeoj.personal_problem_list TO 'forgeoj_api'@'%';
GRANT UPDATE(title,version,updated_at) ON forgeoj.personal_problem_list TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT,DELETE ON forgeoj.personal_problem_list_item TO 'forgeoj_api'@'%';
GRANT UPDATE(position) ON forgeoj.personal_problem_list_item TO 'forgeoj_api'@'%';
GRANT SELECT ON forgeoj.official_problem_list TO 'forgeoj_api'@'%';
GRANT SELECT ON forgeoj.official_problem_list_item TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.user_code_draft TO 'forgeoj_api'@'%';
GRANT UPDATE(source_code,version,updated_at) ON forgeoj.user_code_draft TO 'forgeoj_api'@'%';
