-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
CREATE TABLE classroom (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 owner_id BIGINT UNSIGNED NOT NULL,
 title VARCHAR(64) NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
 invite_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 invite_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT UNSIGNED NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_classroom_invite(invite_sha256),
 KEY idx_classroom_owner(owner_id),
 FOREIGN KEY(owner_id) REFERENCES user_account(id),
 CHECK(status IN ('ACTIVE','ARCHIVED')),
 CHECK(version BETWEEN 1 AND 9007199254740991),
 CHECK(NOT invite_enabled OR invite_sha256 IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE classroom_member (
 classroom_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id BIGINT UNSIGNED NOT NULL,
 role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
 joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 active_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (CASE WHEN role='OWNER' AND status='ACTIVE' THEN classroom_id ELSE NULL END) STORED,
 PRIMARY KEY(classroom_id,user_id),
 UNIQUE KEY uq_classroom_active_owner(active_owner),
 KEY idx_member_user(user_id,classroom_id),
 FOREIGN KEY(classroom_id) REFERENCES classroom(id),
 FOREIGN KEY(user_id) REFERENCES user_account(id),
 CHECK(role IN ('OWNER','ASSISTANT','MEMBER')),
 CHECK(status IN ('ACTIVE','LEFT','REMOVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE classroom_transfer (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 classroom_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 from_user_id BIGINT UNSIGNED NOT NULL,
 target_user_id BIGINT UNSIGNED NOT NULL,
 client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
 pending_classroom CHAR(36) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (CASE WHEN status='PENDING' THEN classroom_id ELSE NULL END) STORED,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 closed_at DATETIME(6) NULL,
 UNIQUE KEY uq_transfer_request(from_user_id,client_request_id),
 UNIQUE KEY uq_pending_transfer(pending_classroom),
 FOREIGN KEY(classroom_id) REFERENCES classroom(id),
 FOREIGN KEY(from_user_id) REFERENCES user_account(id),
 FOREIGN KEY(target_user_id) REFERENCES user_account(id),
 CHECK(from_user_id<>target_user_id),
 CHECK(status IN ('PENDING','ACCEPTED','WITHDRAWN')),
 CHECK((status='PENDING' AND closed_at IS NULL) OR (status<>'PENDING' AND closed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE classroom_creation_request (
 user_id BIGINT UNSIGNED NOT NULL,
 client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 classroom_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 title VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(user_id,client_request_id),
 FOREIGN KEY(user_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
GRANT SELECT,INSERT,DELETE ON forgeoj.classroom TO 'forgeoj_api'@'%';
GRANT UPDATE(owner_id,title,status,invite_sha256,invite_enabled,version,updated_at) ON forgeoj.classroom TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT,DELETE ON forgeoj.classroom_member TO 'forgeoj_api'@'%';
GRANT UPDATE(role,status,updated_at) ON forgeoj.classroom_member TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.classroom_transfer TO 'forgeoj_api'@'%';
GRANT UPDATE(status,closed_at) ON forgeoj.classroom_transfer TO 'forgeoj_api'@'%';
GRANT SELECT,INSERT ON forgeoj.classroom_creation_request TO 'forgeoj_api'@'%';
