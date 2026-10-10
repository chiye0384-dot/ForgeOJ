-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
ALTER TABLE problem ADD UNIQUE KEY uq_problem_search_scope(id,scope);
CREATE TABLE public_search_version (
 problem_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PUBLIC',
 data_version BIGINT UNSIGNED NOT NULL,
 FOREIGN KEY(problem_id,scope) REFERENCES problem(id,scope),
 CHECK(scope='PUBLIC'), CHECK(data_version BETWEEN 1 AND 9007199254740991)
) ENGINE=InnoDB;
CREATE TABLE public_search_outbox (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 problem_id BIGINT UNSIGNED NOT NULL,
 data_version BIGINT UNSIGNED NOT NULL,
 public_epoch BIGINT UNSIGNED NOT NULL,
 publish_attempts INT UNSIGNED NOT NULL DEFAULT 0,
 next_publish_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 published_at DATETIME(6) NULL, failed_at DATETIME(6) NULL, error_code VARCHAR(32) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_public_search_event(problem_id,data_version),
 INDEX ix_public_search_due(published_at,failed_at,next_publish_at),
 FOREIGN KEY(problem_id) REFERENCES public_search_version(problem_id),
 CHECK(data_version BETWEEN 1 AND 9007199254740991), CHECK(public_epoch BETWEEN 1 AND 9007199254740991),
 CHECK(publish_attempts<=5)
) ENGINE=InnoDB;
INSERT INTO public_search_version(problem_id,data_version) SELECT id,1 FROM problem WHERE scope='PUBLIC';
INSERT INTO public_search_outbox(id,problem_id,data_version,public_epoch)
 SELECT UUID(),problem_id,1,(SELECT revision FROM cache_epoch WHERE namespace='public') FROM public_search_version;
CREATE TABLE public_search_delivery (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 status VARCHAR(16) NOT NULL DEFAULT 'QUEUED', attempts INT UNSIGNED NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_expires_at DATETIME(6) NULL, error_code VARCHAR(32) NULL,
 finished_at DATETIME(6) NULL,
 FOREIGN KEY(event_id) REFERENCES public_search_outbox(id),
 CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','DEAD_LETTER')),
 CHECK(attempts<=5), CHECK((lease_token IS NULL)=(lease_expires_at IS NULL)),
 INDEX ix_search_delivery_due(status,next_attempt_at,lease_expires_at)
) ENGINE=InnoDB;
CREATE TABLE public_search_dead_outbox (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 attempts INT UNSIGNED NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 published_at DATETIME(6) NULL, failed_at DATETIME(6) NULL, error_code VARCHAR(32) NULL,
 FOREIGN KEY(event_id) REFERENCES public_search_delivery(event_id), CHECK(attempts<=5)
) ENGINE=InnoDB;
CREATE TABLE public_search_control (
 id TINYINT NOT NULL PRIMARY KEY, version BIGINT UNSIGNED NOT NULL DEFAULT 1,
 readable_epoch BIGINT UNSIGNED NOT NULL DEFAULT 0,
 index_name VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NULL,
 index_uuid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 active_job CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 CHECK(id=1), CHECK(version BETWEEN 1 AND 9007199254740991)
) ENGINE=InnoDB;
INSERT INTO public_search_control(id) VALUES(1);
CREATE TABLE public_search_rebuild (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 actor_id BIGINT UNSIGNED NOT NULL,
 client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reason VARCHAR(500) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
 target_index VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 target_uuid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 target_epoch BIGINT UNSIGNED NULL, attempts INT UNSIGNED NOT NULL DEFAULT 0,
 lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_expires_at DATETIME(6) NULL,
 error_code VARCHAR(32) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), finished_at DATETIME(6) NULL,
 UNIQUE KEY uq_public_search_rebuild_request(actor_id,client_request_id),
 FOREIGN KEY(actor_id) REFERENCES admin_account(id),
 CHECK(status IN ('QUEUED','RUNNING','READY','SUCCEEDED','FAILED')), CHECK(attempts<=5),
 CHECK((lease_token IS NULL)=(lease_expires_at IS NULL))
) ENGINE=InnoDB;
-- A separately audited rebuild can republish an exhausted dead event without resetting its facts.
CREATE TABLE public_search_dead_recovery (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 rebuild_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 attempts INT UNSIGNED NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 published_at DATETIME(6) NULL, failed_at DATETIME(6) NULL, error_code VARCHAR(32) NULL,
 PRIMARY KEY(event_id,rebuild_id),
 FOREIGN KEY(event_id) REFERENCES public_search_dead_outbox(event_id),
 FOREIGN KEY(rebuild_id) REFERENCES public_search_rebuild(id), CHECK(attempts<=5)
) ENGINE=InnoDB;
GRANT SELECT,INSERT ON forgeoj.public_search_dead_recovery TO 'forgeoj_api'@'%';
GRANT UPDATE(attempts,next_attempt_at,published_at,failed_at,error_code) ON forgeoj.public_search_dead_recovery TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.public_search_version TO 'forgeoj_api'@'%';
GRANT UPDATE(data_version) ON forgeoj.public_search_version TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.public_search_outbox TO 'forgeoj_api'@'%';
GRANT UPDATE(publish_attempts,next_publish_at,published_at,failed_at,error_code) ON forgeoj.public_search_outbox TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.public_search_delivery TO 'forgeoj_api'@'%';
GRANT UPDATE(status,attempts,next_attempt_at,lease_token,lease_expires_at,error_code,finished_at) ON forgeoj.public_search_delivery TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.public_search_dead_outbox TO 'forgeoj_api'@'%';
GRANT UPDATE(attempts,next_attempt_at,published_at,failed_at,error_code) ON forgeoj.public_search_dead_outbox TO 'forgeoj_api'@'%';
GRANT SELECT ON forgeoj.public_search_control TO 'forgeoj_api'@'%';
GRANT UPDATE(version,readable_epoch,index_name,index_uuid,active_job) ON forgeoj.public_search_control TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.public_search_rebuild TO 'forgeoj_api'@'%';
GRANT UPDATE(status,target_uuid,target_epoch,attempts,lease_token,lease_expires_at,error_code,finished_at) ON forgeoj.public_search_rebuild TO 'forgeoj_api'@'%';
