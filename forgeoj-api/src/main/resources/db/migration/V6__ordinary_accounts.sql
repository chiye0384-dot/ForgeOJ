-- Copyright 2026 池也
-- SPDX-License-Identifier: Apache-2.0
ALTER TABLE user_account
    MODIFY COLUMN status VARCHAR(32) NOT NULL,
    ADD COLUMN email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN email_verified_at DATETIME(6) NULL,
    ADD COLUMN nickname VARCHAR(64) NULL,
    DROP CHECK chk_user_account_status,
    ADD CONSTRAINT chk_user_account_status CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED')),
    ADD CONSTRAINT uq_user_account_email UNIQUE (email);

CREATE TABLE login_session (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_login_session_user (user_id, revoked_at),
    CONSTRAINT fk_login_session_user FOREIGN KEY (user_id) REFERENCES user_account (id)
) ENGINE=InnoDB;

CREATE TABLE refresh_token (
    token_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    consumed_at DATETIME(6) NULL,
    PRIMARY KEY (token_sha256),
    CONSTRAINT fk_refresh_token_session FOREIGN KEY (session_id) REFERENCES login_session (id)
) ENGINE=InnoDB;

CREATE TABLE account_action_token (
    token_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    target_email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    PRIMARY KEY (token_sha256),
    INDEX idx_action_token_user_purpose (user_id, purpose, created_at),
    CONSTRAINT fk_action_token_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT chk_action_token_purpose CHECK (purpose IN ('ACTIVATE', 'RESET_PASSWORD', 'BIND_EMAIL'))
) ENGINE=InnoDB;

GRANT INSERT ON forgeoj.user_account TO 'forgeoj_api'@'%';
GRANT UPDATE (password_hash, status, email, email_verified_at, nickname)
    ON forgeoj.user_account TO 'forgeoj_api'@'%';
GRANT SELECT, INSERT ON forgeoj.login_session TO 'forgeoj_api'@'%';
GRANT UPDATE (revoked_at) ON forgeoj.login_session TO 'forgeoj_api'@'%';
GRANT SELECT, INSERT ON forgeoj.refresh_token TO 'forgeoj_api'@'%';
GRANT UPDATE (consumed_at) ON forgeoj.refresh_token TO 'forgeoj_api'@'%';
GRANT SELECT, INSERT ON forgeoj.account_action_token TO 'forgeoj_api'@'%';
GRANT UPDATE (consumed_at) ON forgeoj.account_action_token TO 'forgeoj_api'@'%';
