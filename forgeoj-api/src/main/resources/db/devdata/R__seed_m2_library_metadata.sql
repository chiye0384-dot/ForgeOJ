-- Copyright 2026 池也
-- SPDX-License-Identifier: Apache-2.0
-- Development-only metadata for the existing original example, never a production migration.
UPDATE problem SET difficulty = 'EASY' WHERE slug = 'sum-two-integers';
INSERT IGNORE INTO problem_tag (problem_id, tag)
SELECT id, '数学' FROM problem WHERE slug = 'sum-two-integers';
INSERT IGNORE INTO problem_tag (problem_id, tag)
SELECT id, '入门' FROM problem WHERE slug = 'sum-two-integers';
