-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Preserve V11/V12 validation semantics; preview never has PASSED or a solution result.
ALTER TABLE content_validation_snapshot ADD execution_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'VALIDATE',
 ADD UNIQUE KEY uq_content_execution_binding(id,owner_id,execution_kind),
 ADD CONSTRAINT chk_content_execution_kind CHECK(execution_kind IN ('VALIDATE','OUTPUT_PREVIEW'));
ALTER TABLE content_validation_snapshot DROP CHECK content_validation_snapshot_chk_4, DROP CHECK content_validation_snapshot_chk_5,
 ADD CONSTRAINT chk_content_solution_code CHECK((execution_kind='VALIDATE' AND OCTET_LENGTH(solution_code) BETWEEN 1 AND 65536) OR (execution_kind='OUTPUT_PREVIEW' AND solution_code='')),
 ADD CONSTRAINT chk_content_solution_idea CHECK((execution_kind='VALIDATE' AND OCTET_LENGTH(solution_idea) BETWEEN 1 AND 65536) OR (execution_kind='OUTPUT_PREVIEW' AND solution_idea=''));
ALTER TABLE content_validation_job ADD execution_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'VALIDATE',
 ADD UNIQUE KEY uq_preview_job_snapshot(id,snapshot_id),
 ADD CONSTRAINT fk_content_execution_kind FOREIGN KEY(snapshot_id,owner_id,execution_kind) REFERENCES content_validation_snapshot(id,owner_id,execution_kind);
ALTER TABLE content_validation_job DROP CHECK content_validation_job_chk_6,
 ADD CONSTRAINT chk_content_execution_result CHECK(
  (processing_status<>'FINISHED' AND validation_status IS NULL AND reference_result IS NULL AND solution_result IS NULL)
  OR (processing_status='FINISHED' AND execution_kind='VALIDATE' AND validation_status IS NOT NULL AND reference_result IS NOT NULL AND solution_result IS NOT NULL
      AND validation_status=IF(reference_result='ACCEPTED' AND solution_result='ACCEPTED','PASSED','FAILED'))
  OR (processing_status='FINISHED' AND execution_kind='OUTPUT_PREVIEW' AND reference_result IS NOT NULL AND validation_status IS NULL AND solution_result IS NULL));
CREATE TABLE content_output_preview_case (
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sequence_no INT UNSIGNED NOT NULL,
 output_gzip MEDIUMBLOB NOT NULL,
 output_bytes BIGINT UNSIGNED NOT NULL,
 output_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(job_id,sequence_no),
 FOREIGN KEY(job_id,snapshot_id) REFERENCES content_validation_job(id,snapshot_id),
 FOREIGN KEY(snapshot_id,sequence_no) REFERENCES content_validation_test_case(snapshot_id,sequence_no),
 CHECK(output_bytes<=1048576)
) ENGINE=InnoDB;
CREATE TABLE content_output_acceptance (
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 draft_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 owner_id BIGINT UNSIGNED NOT NULL,
 expected_version BIGINT UNSIGNED NOT NULL,
 applied_version BIGINT UNSIGNED NOT NULL,
 accepted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(job_id) REFERENCES content_validation_job(id),
 FOREIGN KEY(draft_id) REFERENCES authored_problem_draft(id),
 FOREIGN KEY(owner_id) REFERENCES user_account(id),
 CHECK(expected_version BETWEEN 1 AND 9007199254740990 AND applied_version=expected_version+1)
) ENGINE=InnoDB;
GRANT SELECT ON forgeoj.content_output_preview_case TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.content_output_acceptance TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.content_output_preview_case TO 'forgeoj_worker'@'%';
-- No Worker acceptance/authoring access; no API output mutation; kinds remain immutable.
