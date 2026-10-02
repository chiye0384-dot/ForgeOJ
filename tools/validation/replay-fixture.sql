-- Copyright 2026 池也
-- SPDX-License-Identifier: Apache-2.0
-- Second owner ONLY in this empty disposable verification DB; not an account API.
INSERT INTO user_account (id, username, password_hash, status)
SELECT 2, 'other-learner', password_hash, 'ACTIVE' FROM user_account WHERE id = 1;
INSERT INTO user_judge_quota_lock (user_id) VALUES (2);
