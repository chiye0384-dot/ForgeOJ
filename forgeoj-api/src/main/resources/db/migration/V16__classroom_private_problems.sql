-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Existing rows remain PUBLIC. Classroom references are restrictive, including archived rows.
ALTER TABLE problem
    ADD COLUMN scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PUBLIC',
    ADD COLUMN classroom_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uq_problem_classroom(classroom_id,id),
    ADD FOREIGN KEY(classroom_id) REFERENCES classroom(id),
    ADD CHECK((scope='PUBLIC' AND classroom_id IS NULL) OR (scope='CLASSROOM' AND classroom_id IS NOT NULL));
CREATE TABLE classroom_problem (
    problem_id BIGINT UNSIGNED PRIMARY KEY,
    classroom_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    draft_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    draft_version BIGINT UNSIGNED NOT NULL,
    validation_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    solution_policy VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_classroom_problem_request(created_by,client_request_id),
    FOREIGN KEY(classroom_id,problem_id) REFERENCES problem(classroom_id,id),
    FOREIGN KEY(snapshot_id,draft_id,created_by,draft_version) REFERENCES content_validation_snapshot(id,draft_id,owner_id,draft_version),
    FOREIGN KEY(validation_job_id,snapshot_id,created_by) REFERENCES content_validation_job(id,snapshot_id,owner_id),
    CHECK(solution_policy IN ('IMMEDIATE','AFTER_AC')),
    CHECK(version BETWEEN 1 AND 9007199254740991)
) ENGINE=InnoDB;
CREATE TABLE classroom_solution_early_view (
    user_id BIGINT UNSIGNED NOT NULL,
    problem_id BIGINT UNSIGNED NOT NULL,
    first_viewed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(user_id,problem_id),
    FOREIGN KEY(user_id) REFERENCES user_account(id),
    FOREIGN KEY(problem_id) REFERENCES classroom_problem(problem_id)
) ENGINE=InnoDB;
GRANT SELECT,INSERT ON forgeoj.classroom_solution_early_view TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.classroom_problem TO 'forgeoj_api'@'%';
GRANT UPDATE(version) ON forgeoj.classroom_problem TO 'forgeoj_api'@'%';
GRANT INSERT ON forgeoj.problem TO 'forgeoj_api'@'%';
GRANT UPDATE(status,current_judge_version_id) ON forgeoj.problem TO 'forgeoj_api'@'%';
GRANT INSERT ON forgeoj.problem_judge_version TO 'forgeoj_api'@'%';
GRANT INSERT ON forgeoj.problem_test_case TO 'forgeoj_api'@'%';
-- API still cannot SELECT hidden formal tests. Worker receives no new grants;
-- classroom identity/publication and personal viewing records remain inaccessible.
