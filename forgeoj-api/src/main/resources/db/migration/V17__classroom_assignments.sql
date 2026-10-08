-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- A bounded single policy fence serializes solution reads with assignment changes.
-- No account FK on the fence: starting another student's assignment never locks their account.
CREATE TABLE assignment_policy_fence (id TINYINT PRIMARY KEY, CHECK(id=1)) ENGINE=InnoDB;
INSERT INTO assignment_policy_fence(id) VALUES(1);
CREATE TABLE classroom_assignment (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    classroom_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    creation_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(100) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
    starts_at DATETIME(6) NULL,
    deadline_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    ended_at DATETIME(6) NULL,
    close_reason VARCHAR(500) NULL,
    accept_existing_ac BOOLEAN NOT NULL,
    allow_late BOOLEAN NOT NULL,
    solution_policy VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_assignment_request(created_by,client_request_id),
    UNIQUE KEY uq_assignment_classroom(id,classroom_id),
    FOREIGN KEY(classroom_id,created_by) REFERENCES classroom_member(classroom_id,user_id),
    CHECK(status IN ('DRAFT','SCHEDULED','ACTIVE','ENDED','CANCELLED','STOPPED')),
    CHECK(solution_policy IN ('IMMEDIATE','AFTER_AC','AFTER_DEADLINE')),
    CHECK(starts_at IS NULL OR deadline_at>starts_at),
    CHECK(started_at IS NULL OR status IN ('ACTIVE','ENDED','CANCELLED')),
    CHECK(version BETWEEN 1 AND 9007199254740991),
    INDEX ix_assignment_due(status,starts_at,deadline_at)
) ENGINE=InnoDB;
CREATE TABLE assignment_problem (
    assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    problem_id BIGINT UNSIGNED NOT NULL,
    judge_version_id BIGINT UNSIGNED NOT NULL,
    problem_slug VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    metadata_text MEDIUMTEXT NOT NULL,
    solution_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ordinal INT UNSIGNED NOT NULL,
    PRIMARY KEY(assignment_id,problem_id),
    UNIQUE KEY uq_assignment_ordinal(assignment_id,ordinal),
    UNIQUE KEY uq_assignment_basis(assignment_id,problem_id,judge_version_id),
    FOREIGN KEY(assignment_id) REFERENCES classroom_assignment(id),
    FOREIGN KEY(problem_id) REFERENCES problem(id),
    FOREIGN KEY(judge_version_id,problem_id) REFERENCES problem_judge_version(id,problem_id),
    FOREIGN KEY(solution_snapshot_id) REFERENCES content_validation_snapshot(id),
    CHECK(ordinal BETWEEN 1 AND 20)
) ENGINE=InnoDB;
-- Editable draft set is separate; publication never grants DELETE on the frozen set.
CREATE TABLE assignment_draft_problem LIKE assignment_problem;
ALTER TABLE assignment_draft_problem
    ADD FOREIGN KEY(assignment_id) REFERENCES classroom_assignment(id),
    ADD FOREIGN KEY(problem_id) REFERENCES problem(id),
    ADD FOREIGN KEY(judge_version_id,problem_id) REFERENCES problem_judge_version(id,problem_id),
    ADD FOREIGN KEY(solution_snapshot_id) REFERENCES content_validation_snapshot(id);
CREATE TABLE assignment_participant (
    assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    classroom_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(assignment_id,user_id),
    FOREIGN KEY(assignment_id,classroom_id) REFERENCES classroom_assignment(id,classroom_id),
    FOREIGN KEY(classroom_id,user_id) REFERENCES classroom_member(classroom_id,user_id)
) ENGINE=InnoDB;
ALTER TABLE submission ADD UNIQUE KEY uq_assignment_submission_basis(id,user_id,problem_id,judge_version_id);
CREATE TABLE assignment_precompletion (
    assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    problem_id BIGINT UNSIGNED NOT NULL,
    judge_version_id BIGINT UNSIGNED NOT NULL,
    submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(assignment_id,user_id,problem_id),
    FOREIGN KEY(assignment_id,user_id) REFERENCES assignment_participant(assignment_id,user_id),
    FOREIGN KEY(assignment_id,problem_id,judge_version_id) REFERENCES assignment_problem(assignment_id,problem_id,judge_version_id),
    FOREIGN KEY(submission_id,user_id,problem_id,judge_version_id) REFERENCES submission(id,user_id,problem_id,judge_version_id)
) ENGINE=InnoDB;
CREATE TABLE assignment_attempt (
    submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    problem_id BIGINT UNSIGNED NOT NULL,
    judge_version_id BIGINT UNSIGNED NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    FOREIGN KEY(submission_id,user_id,problem_id,judge_version_id) REFERENCES submission(id,user_id,problem_id,judge_version_id),
    FOREIGN KEY(assignment_id,user_id) REFERENCES assignment_participant(assignment_id,user_id),
    FOREIGN KEY(assignment_id,problem_id,judge_version_id) REFERENCES assignment_problem(assignment_id,problem_id,judge_version_id),
    INDEX ix_assignment_attempt(assignment_id,user_id,problem_id,accepted_at)
) ENGINE=InnoDB;
CREATE TABLE assignment_self_test (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    problem_id BIGINT UNSIGNED NOT NULL,
    judge_version_id BIGINT UNSIGNED NOT NULL,
    FOREIGN KEY(run_id) REFERENCES self_test_job(id),
    FOREIGN KEY(assignment_id,user_id) REFERENCES assignment_participant(assignment_id,user_id),
    FOREIGN KEY(assignment_id,problem_id,judge_version_id) REFERENCES assignment_problem(assignment_id,problem_id,judge_version_id)
) ENGINE=InnoDB;
GRANT SELECT,INSERT ON forgeoj.assignment_self_test TO 'forgeoj_api'@'%';
GRANT SELECT,UPDATE(id) ON forgeoj.assignment_policy_fence TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.classroom_assignment TO 'forgeoj_api'@'%';
GRANT UPDATE(title,description,status,starts_at,deadline_at,started_at,ended_at,close_reason,accept_existing_ac,allow_late,solution_policy,version) ON forgeoj.classroom_assignment TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.assignment_problem TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT,DELETE ON forgeoj.assignment_draft_problem TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.assignment_participant TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.assignment_precompletion TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.assignment_attempt TO 'forgeoj_api'@'%';
-- Worker receives no access to assignments, participants, or historical private AC relations.
