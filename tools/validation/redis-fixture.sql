-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Only the unique disposable replay database. Copy its existing PUBLIC test credential, never a real account.
INSERT INTO user_account(username,password_hash,status)
 SELECT 'redis_learner',password_hash,'ACTIVE' FROM user_account WHERE username='learner'
 AND NOT EXISTS (SELECT 1 FROM user_account existing WHERE existing.username='redis_learner');
INSERT INTO user_judge_quota_lock(user_id) SELECT a.id FROM user_account a WHERE a.username='redis_learner'
 AND NOT EXISTS (SELECT 1 FROM user_judge_quota_lock existing WHERE existing.user_id=a.id);
