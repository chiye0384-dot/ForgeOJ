-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Only the future validated/reviewed publication transaction may create these snapshots.
-- No seeds, no ordinary-account authoring permission, no link exposing private references.
CREATE TABLE official_problem_solution (
    judge_version_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    idea MEDIUMTEXT NOT NULL,
    language VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_code MEDIUMTEXT NOT NULL,
    published_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY(judge_version_id) REFERENCES problem_judge_version(id),
    CHECK(language='JAVA_21'),
    CHECK(OCTET_LENGTH(idea) BETWEEN 1 AND 65536),
    CHECK(OCTET_LENGTH(source_code) BETWEEN 1 AND 65536)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE user_solution_early_view (
    user_id BIGINT UNSIGNED NOT NULL,
    judge_version_id BIGINT UNSIGNED NOT NULL,
    first_viewed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(user_id,judge_version_id),
    FOREIGN KEY(user_id) REFERENCES user_account(id),
    FOREIGN KEY(judge_version_id) REFERENCES official_problem_solution(judge_version_id)
) ENGINE=InnoDB;
GRANT SELECT ON forgeoj.official_problem_solution TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.user_solution_early_view TO 'forgeoj_api'@'%';
-- Worker has no access to either learning/publication table.
