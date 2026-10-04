-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Author submission/withdrawal only; no reviewer, publication or synthetic PASSED seed.
ALTER TABLE content_validation_snapshot ADD UNIQUE KEY uq_validation_review_binding(id,draft_id,owner_id,draft_version);
ALTER TABLE content_validation_job ADD UNIQUE KEY uq_validation_review_job(id,snapshot_id,owner_id);
CREATE TABLE content_review (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    draft_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BIGINT UNSIGNED NOT NULL,
    draft_version BIGINT UNSIGNED NOT NULL,
    validation_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_no BIGINT UNSIGNED NOT NULL,
    review_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    submitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    withdrawn_at DATETIME(6) NULL,
    active_draft_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN review_status='PENDING' THEN draft_id ELSE NULL END) STORED,
    UNIQUE KEY uq_review_request(owner_id,request_id),
    UNIQUE KEY uq_review_number(draft_id,review_no),
    UNIQUE KEY uq_review_active(active_draft_id),
    FOREIGN KEY(snapshot_id,draft_id,owner_id,draft_version) REFERENCES content_validation_snapshot(id,draft_id,owner_id,draft_version),
    FOREIGN KEY(validation_job_id,snapshot_id,owner_id) REFERENCES content_validation_job(id,snapshot_id,owner_id),
    INDEX idx_review_owner(owner_id,draft_id,submitted_at,id),
    CHECK(review_no BETWEEN 1 AND 9007199254740991),
    CHECK((review_status='PENDING' AND version=0 AND withdrawn_at IS NULL)
       OR (review_status='WITHDRAWN' AND version=1 AND withdrawn_at IS NOT NULL))
) ENGINE=InnoDB;
GRANT SELECT,INSERT ON forgeoj.content_review TO 'forgeoj_api'@'%';
GRANT UPDATE(review_status,version,withdrawn_at) ON forgeoj.content_review TO 'forgeoj_api'@'%';
-- Worker cannot read pending review identity or change any review lifecycle field.
