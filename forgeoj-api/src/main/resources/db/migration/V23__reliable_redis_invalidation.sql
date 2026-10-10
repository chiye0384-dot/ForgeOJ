-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Explicit transactional invalidation; no global privilege or trigger-policy change.
CREATE TABLE cache_epoch (
 namespace VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 revision BIGINT UNSIGNED NOT NULL,
 CHECK(revision BETWEEN 1 AND 9007199254740991)
) ENGINE=InnoDB;
INSERT INTO cache_epoch(namespace,revision) VALUES('public',1);
CREATE TABLE cache_invalidation_outbox (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 namespace VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 old_revision BIGINT UNSIGNED NOT NULL,
 attempts INT UNSIGNED NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 delivered_at DATETIME(6) NULL,
 error_code VARCHAR(32) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_cache_invalidation(namespace,old_revision),
 KEY ix_cache_invalidation_due(delivered_at,next_attempt_at,id)
) ENGINE=InnoDB;
GRANT SELECT ON forgeoj.cache_epoch TO 'forgeoj_api'@'%';
GRANT UPDATE(revision) ON forgeoj.cache_epoch TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.cache_invalidation_outbox TO 'forgeoj_api'@'%';
GRANT UPDATE(attempts,next_attempt_at,delivered_at,error_code) ON forgeoj.cache_invalidation_outbox TO 'forgeoj_api'@'%';
