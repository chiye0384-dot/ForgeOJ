-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Private authoring only. No public problem, official solution or successful validation seed.
CREATE TABLE authored_problem_draft (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    owner_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(100) NOT NULL,
    metadata_json JSON NOT NULL,
    reference_code MEDIUMTEXT NOT NULL,
    solution_idea MEDIUMTEXT NOT NULL,
    solution_code MEDIUMTEXT NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY(owner_id) REFERENCES user_account(id),
    INDEX idx_authored_draft_owner(owner_id,created_at,id),
    CHECK(CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 100),
    CHECK(version BETWEEN 1 AND 9007199254740991),
    CHECK(status IN ('DRAFT','ARCHIVED')),
    CHECK(OCTET_LENGTH(reference_code)<=65536),
    CHECK(OCTET_LENGTH(solution_code)<=65536),
    CHECK(OCTET_LENGTH(solution_idea)<=65536)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE authored_problem_test_case (
    draft_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no INT UNSIGNED NOT NULL,
    input_gzip MEDIUMBLOB NOT NULL,
    expected_output_gzip MEDIUMBLOB NOT NULL,
    input_bytes BIGINT UNSIGNED NOT NULL,
    expected_output_bytes BIGINT UNSIGNED NOT NULL,
    input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_output_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(draft_id,sequence_no),
    FOREIGN KEY(draft_id) REFERENCES authored_problem_draft(id),
    CHECK(sequence_no BETWEEN 1 AND 100),
    CHECK(input_bytes<=1048576),
    CHECK(expected_output_bytes<=1048576)
) ENGINE=InnoDB;
GRANT SELECT,INSERT,DELETE ON forgeoj.authored_problem_draft TO 'forgeoj_api'@'%';
GRANT UPDATE(title,metadata_json,reference_code,solution_idea,solution_code,version,status,updated_at)
    ON forgeoj.authored_problem_draft TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT,DELETE ON forgeoj.authored_problem_test_case TO 'forgeoj_api'@'%';
-- Worker gets no mutable authoring access. Immutable validation snapshots come in a later migration.
