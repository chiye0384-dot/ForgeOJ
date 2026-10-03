-- Copyright 2026 池也
-- SPDX-License-Identifier: Apache-2.0
ALTER TABLE problem
    ADD COLUMN difficulty VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT chk_problem_difficulty CHECK (
        difficulty IS NULL OR difficulty IN ('EASY', 'MEDIUM', 'HARD')
    );

CREATE TABLE problem_tag (
    problem_id BIGINT UNSIGNED NOT NULL,
    tag VARCHAR(32) NOT NULL,
    PRIMARY KEY (problem_id, tag),
    INDEX idx_problem_tag_name (tag, problem_id),
    CONSTRAINT fk_problem_tag_problem FOREIGN KEY (problem_id) REFERENCES problem (id),
    CONSTRAINT chk_problem_tag_nonempty CHECK (CHAR_LENGTH(TRIM(tag)) > 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

GRANT SELECT ON forgeoj.problem_tag TO 'forgeoj_api'@'%';
