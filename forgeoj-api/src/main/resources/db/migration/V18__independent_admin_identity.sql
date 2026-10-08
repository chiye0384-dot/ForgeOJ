-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
CREATE TABLE admin_account (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL UNIQUE,
    password_hash VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    role VARCHAR(32) NOT NULL,
    must_change_password BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CHECK(status IN ('ACTIVE','DISABLED')),
    CHECK(role IN ('CONTENT_REVIEWER','OPS_ADMIN','SUPER_ADMIN')),
    CHECK(version BETWEEN 1 AND 9007199254740991)
) ENGINE=InnoDB;
CREATE TABLE admin_policy_fence (
    id TINYINT NOT NULL PRIMARY KEY,
    bootstrapped BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK(id=1)
) ENGINE=InnoDB;
INSERT INTO admin_policy_fence(id,bootstrapped) VALUES(1,FALSE);
CREATE TABLE admin_login_session (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    admin_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    FOREIGN KEY(admin_id) REFERENCES admin_account(id),
    INDEX ix_admin_session(admin_id,revoked_at),
    CHECK(expires_at>created_at)
) ENGINE=InnoDB;
CREATE TABLE admin_refresh_token (
    token_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    FOREIGN KEY(session_id) REFERENCES admin_login_session(id)
) ENGINE=InnoDB;
CREATE TABLE admin_creation_request (
    actor_id BIGINT UNSIGNED NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    public_request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(actor_id,client_request_id),
    FOREIGN KEY(actor_id) REFERENCES admin_account(id),
    FOREIGN KEY(target_id) REFERENCES admin_account(id)
) ENGINE=InnoDB;
CREATE TABLE admin_audit_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    occurred_at DATETIME(6) NOT NULL,
    actor_type VARCHAR(24) NOT NULL,
    actor_admin_id BIGINT UNSIGNED NULL,
    action VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    outcome VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    before_state VARCHAR(256) NULL,
    after_state VARCHAR(256) NULL,
    correlation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    FOREIGN KEY(actor_admin_id) REFERENCES admin_account(id),
    INDEX ix_admin_audit_time(occurred_at,id),
    INDEX ix_admin_audit_actor(actor_admin_id,occurred_at),
    CHECK(actor_type IN ('ADMIN','BOOTSTRAP','LOCAL_RECOVERY','AUTH_ANONYMOUS','DENIED_USER')),
    CHECK(outcome IN ('SUCCESS','DENIED','FAILED')),
    CHECK(CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 500)
) ENGINE=InnoDB;
GRANT SELECT,INSERT ON forgeoj.admin_account TO 'forgeoj_api'@'%';
GRANT UPDATE(password_hash,status,role,must_change_password,version,updated_at) ON forgeoj.admin_account TO 'forgeoj_api'@'%';
GRANT SELECT,UPDATE(id) ON forgeoj.admin_policy_fence TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.admin_login_session TO 'forgeoj_api'@'%';
GRANT UPDATE(revoked_at) ON forgeoj.admin_login_session TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.admin_refresh_token TO 'forgeoj_api'@'%';
GRANT UPDATE(consumed_at) ON forgeoj.admin_refresh_token TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.admin_creation_request TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.admin_audit_event TO 'forgeoj_api'@'%';
-- No Worker grants, default admin, ordinary-user identity or bootstrap at Web startup.
