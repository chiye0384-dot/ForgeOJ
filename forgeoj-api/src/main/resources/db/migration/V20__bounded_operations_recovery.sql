-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Read-only attempt metadata supports audited OPS projections. No execution or snapshot writes.
GRANT SELECT ON forgeoj.judge_task_attempt TO 'forgeoj_api'@'%';
GRANT SELECT ON forgeoj.content_validation_attempt TO 'forgeoj_api'@'%';
GRANT SELECT ON forgeoj.self_test_attempt TO 'forgeoj_api'@'%';
