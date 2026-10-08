-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Keep immutable snapshots, historical judging and ordinary/admin identities separate.
ALTER TABLE problem MODIFY statement_text MEDIUMTEXT NOT NULL;
ALTER TABLE content_review DROP CHECK content_review_chk_2,
 ADD latest_recheck_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD CONSTRAINT fk_review_recheck FOREIGN KEY(latest_recheck_id,snapshot_id,owner_id)
 REFERENCES content_validation_job(id,snapshot_id,owner_id),
 ADD CONSTRAINT chk_review_lifecycle CHECK(
  (review_status='PENDING' AND version=0 AND withdrawn_at IS NULL)
  OR (review_status='WITHDRAWN' AND version=1 AND withdrawn_at IS NOT NULL)
  OR (review_status IN ('APPROVED','REJECTED') AND version=1 AND withdrawn_at IS NULL));

CREATE TABLE public_problem_governance (
 problem_id BIGINT UNSIGNED PRIMARY KEY,
 version BIGINT UNSIGNED NOT NULL DEFAULT 1,
 author_id BIGINT UNSIGNED NULL,
 current_review_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 correction_of_id BIGINT UNSIGNED NULL,
 data_invalid BOOLEAN NOT NULL DEFAULT FALSE,
 invalid_reason VARCHAR(500) NULL,
 state_reason VARCHAR(500) NULL,
 FOREIGN KEY(problem_id) REFERENCES problem(id),
 FOREIGN KEY(author_id) REFERENCES user_account(id),
 FOREIGN KEY(current_review_id) REFERENCES content_review(id),
 FOREIGN KEY(snapshot_id) REFERENCES content_validation_snapshot(id),
 FOREIGN KEY(correction_of_id) REFERENCES problem(id),
 CHECK(version BETWEEN 1 AND 9007199254740991),
 CHECK((data_invalid=FALSE AND invalid_reason IS NULL) OR (data_invalid=TRUE AND CHAR_LENGTH(TRIM(invalid_reason)) BETWEEN 1 AND 500)),
 CHECK(correction_of_id IS NULL OR correction_of_id<>problem_id)
) ENGINE=InnoDB;
-- Registry only; no synthetic review, reference, successful validation or admin.
INSERT INTO public_problem_governance(problem_id) SELECT id FROM problem WHERE scope='PUBLIC';

ALTER TABLE authored_problem_draft ADD UNIQUE KEY uq_public_revision_draft_owner(id,owner_id);
CREATE TABLE public_revision_draft (
 draft_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 problem_id BIGINT UNSIGNED NOT NULL,
 owner_id BIGINT UNSIGNED NOT NULL,
 client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expected_version BIGINT UNSIGNED NOT NULL,
 revision_kind VARCHAR(16) NOT NULL,
 UNIQUE KEY uq_public_revision_request(owner_id,client_request_id),
 FOREIGN KEY(draft_id,owner_id) REFERENCES authored_problem_draft(id,owner_id),
 FOREIGN KEY(problem_id) REFERENCES public_problem_governance(problem_id),
 FOREIGN KEY(owner_id) REFERENCES user_account(id),
 CHECK(expected_version BETWEEN 1 AND 9007199254740991),
 CHECK(revision_kind IN ('TEXT','CORRECTION'))
) ENGINE=InnoDB;

CREATE TABLE public_review_decision (
 review_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 actor_admin_id BIGINT UNSIGNED NOT NULL,
 client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 public_request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 decision VARCHAR(16) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 problem_id BIGINT UNSIGNED NULL,
 decided_at DATETIME(6) NOT NULL,
 UNIQUE KEY uq_public_decision_request(actor_admin_id,client_request_id),
 FOREIGN KEY(review_id) REFERENCES content_review(id),
 FOREIGN KEY(actor_admin_id) REFERENCES admin_account(id),
 FOREIGN KEY(problem_id) REFERENCES problem(id),
 CHECK(decision IN ('APPROVED','REJECTED')),
 CHECK((decision='APPROVED' AND problem_id IS NOT NULL) OR (decision='REJECTED' AND problem_id IS NULL)),
 CHECK(CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 500)
) ENGINE=InnoDB;

CREATE TABLE public_review_recheck (
 actor_admin_id BIGINT UNSIGNED NOT NULL,
 client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 review_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 public_request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(actor_admin_id,client_request_id),
 UNIQUE KEY uq_review_recheck_job(job_id),
 FOREIGN KEY(actor_admin_id) REFERENCES admin_account(id),
 FOREIGN KEY(review_id) REFERENCES content_review(id),
 FOREIGN KEY(job_id) REFERENCES content_validation_job(id)
) ENGINE=InnoDB;

CREATE TABLE public_problem_feedback_case (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 problem_id BIGINT UNSIGNED NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
 version BIGINT UNSIGNED NOT NULL DEFAULT 1,
 resolution VARCHAR(500) NULL,
 resolved_by BIGINT UNSIGNED NULL,
 created_at DATETIME(6) NOT NULL,
 closed_at DATETIME(6) NULL,
 active_problem_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status='OPEN' THEN problem_id ELSE NULL END) STORED,
 UNIQUE KEY uq_public_feedback_open(active_problem_id),
 FOREIGN KEY(problem_id) REFERENCES problem(id),
 FOREIGN KEY(resolved_by) REFERENCES admin_account(id),
 CHECK((status='OPEN' AND resolution IS NULL AND resolved_by IS NULL AND closed_at IS NULL)
 OR (status='CLOSED' AND CHAR_LENGTH(TRIM(resolution)) BETWEEN 1 AND 500 AND resolved_by IS NOT NULL AND closed_at IS NOT NULL)),
 CHECK(version BETWEEN 1 AND 9007199254740991)
) ENGINE=InnoDB;
CREATE TABLE public_problem_feedback (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id BIGINT UNSIGNED NOT NULL,
 client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 category VARCHAR(32) NOT NULL,
 body VARCHAR(2000) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uq_public_feedback_user(case_id,user_id),
 UNIQUE KEY uq_public_feedback_request(user_id,client_request_id),
 FOREIGN KEY(case_id) REFERENCES public_problem_feedback_case(id),
 FOREIGN KEY(user_id) REFERENCES user_account(id),
 CHECK(category IN ('AMBIGUITY','SAMPLE_ERROR','TEST_ERROR','RESOURCE_LIMIT','COPYRIGHT','OTHER')),
 CHECK(CHAR_LENGTH(TRIM(body)) BETWEEN 1 AND 2000)
) ENGINE=InnoDB;

GRANT SELECT,INSERT ON forgeoj.public_problem_governance TO 'forgeoj_api'@'%';
GRANT UPDATE(version,author_id,current_review_id,snapshot_id,correction_of_id,data_invalid,invalid_reason,state_reason) ON forgeoj.public_problem_governance TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.public_revision_draft TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.public_review_decision TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.public_review_recheck TO 'forgeoj_api'@'%';
GRANT UPDATE(latest_recheck_id) ON forgeoj.content_review TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.public_problem_feedback_case TO 'forgeoj_api'@'%';
GRANT UPDATE(status,version,resolution,resolved_by,closed_at) ON forgeoj.public_problem_feedback_case TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.public_problem_feedback TO 'forgeoj_api'@'%';
GRANT UPDATE(title,statement_text,input_description,output_description,public_samples_json) ON forgeoj.problem TO 'forgeoj_api'@'%';
GRANT INSERT ON forgeoj.official_problem_solution TO 'forgeoj_api'@'%';
-- Official solution corrections keep the same judge only after a new frozen dual-PASSED review.
GRANT UPDATE(idea,source_code,published_at) ON forgeoj.official_problem_solution TO 'forgeoj_api'@'%';
-- No new Worker grants, publication defaults, admin account, SQL or hidden-test endpoint.
