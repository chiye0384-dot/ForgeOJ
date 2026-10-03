-- Copyright 2026 池也
-- SPDX-License-Identifier: Apache-2.0
-- Second owner ONLY in this empty disposable verification DB; not an account API.
INSERT INTO user_account (id, username, password_hash, status)
SELECT 2, 'other-learner', password_hash, 'ACTIVE' FROM user_account WHERE id = 1;
INSERT INTO user_judge_quota_lock (user_id) VALUES (2);

-- Original second problem ONLY for this empty disposable replay, no public release/review claim.
-- Dataset manifest is ASCII "ordinal:inputSHA256:outputSHA256\n" in ordinal order.
INSERT INTO problem (id, slug, title, statement_text, input_description, output_description,
 public_samples_json, status, difficulty, current_judge_version_id)
VALUES (100, 'larger-of-two-integers', '两数较大值',
 '读入两个 64 位有符号整数，输出其中较大的一个；两数相等时输出该值。',
 '一行包含以空格分隔的整数 a 和 b。', '输出一行，包含 a 与 b 的较大值。',
 JSON_ARRAY(JSON_OBJECT('input','1 2\n','output','2\n')), 'ACTIVE', 'EASY', NULL);
INSERT INTO problem_judge_version (id, problem_id, version_no, time_limit_ms, memory_limit_mb,
 output_limit_bytes, comparison_rule_version, sandbox_policy_version, java_image_digest,
 test_dataset_sha256)
SELECT 100, 100, 1, time_limit_ms, memory_limit_mb, output_limit_bytes, comparison_rule_version,
 sandbox_policy_version, java_image_digest,
 'd47f4f0229ec078f435e5fc51a41af41389f2d4288ae9640f0f1412a50d8b16c'
FROM problem_judge_version WHERE id = 1;
UPDATE problem SET current_judge_version_id = 100 WHERE id = 100;
INSERT INTO problem_tag (problem_id, tag) VALUES (100,'数学'), (100,'比较');
INSERT INTO problem_test_case (id, judge_version_id, ordinal, input_data_gzip, expected_output_gzip,
 input_size_bytes, output_size_bytes, input_sha256, output_sha256) VALUES
(100,100,1,UNHEX('1f8b080000000000020a335430e2020057bb3b5c04000000'),
 UNHEX('1f8b080000000000020a33e2020090af7c4c02000000'),4,2,
 'f251ddc12234e0da8d3b778bd0f7463fb477f16f47757f5617dc8b4ff4d4f14a',
 '53c234e5e8472b6ac51c1ae1cab3fe06fad053beb8ebfd8977b010655bfdd3c3'),
(101,100,2,UNHEX('1f8b080000000000020ad33551b0e402001a51265605000000'),
 UNHEX('1f8b080000000000020ab3e402005b7688af02000000'),5,2,
 'cfb54cf575309eaee1116780da5f3cf2e4bf123d2e482cc55c4da4ad166f4e48',
 '2e6d31a5983a91251bfae5aefa1c0a19d8ba3cf601d0e8a706b4cfa9661a6b8a'),
(102,100,3,UNHEX('1f8b080000000000020a3332343137b1303633315730e40200293184bc0d000000'),
 UNHEX('1f8b080000000000020a3332343137b130363331e70200410411b40b000000'),13,11,
 'cd8795cb0610cab6d1323fbf2ccee015a8ed2e1b45783c9f2d86484fc90e8edd',
 '4225294e0b1c479988ce8e032ef662c1166728a7a0c1f6bbefef1d45a2b02581'),
(103,100,4,UNHEX('1f8b080000000000020ad3b5343232363637323036b33035313737b530b050d035e40200927bfdf718000000'),
 UNHEX('1f8b080000000000020ad335e402000de25ce903000000'),24,3,
 '8c2632dafa0b1b22ea9009cc14852c3a28b75352f9325f8434b366678d912833',
 'ee3aa64bb94a50845d5024cd4bd20202a4567aed5cd5328c0d97e9920775fc28');
