INSERT INTO user_account (id, username, password_hash, status)
VALUES (1, 'learner', '$2a$10$slWnrjf2WJd.j/4Fnc1m..7QjwDgtyAlJ4OrLRzEjhAN0g7zkuyCi', 'ACTIVE') AS new
ON DUPLICATE KEY UPDATE
    password_hash = new.password_hash,
    status = new.status;

INSERT INTO problem (
    id,
    slug,
    title,
    statement_text,
    input_description,
    output_description,
    public_samples_json,
    status,
    current_judge_version_id
)
VALUES (
    1,
    'sum-two-integers',
    '两数之和',
    '读入两个有符号整数，输出它们的和。输入和结果均在 64 位有符号整数范围内。',
    '一行包含两个以空格分隔的整数 a 和 b。',
    '输出一行，包含 a + b 的值。',
    JSON_ARRAY(JSON_OBJECT('input', '1 2\n', 'output', '3\n')),
    'ACTIVE',
    NULL
) AS new
ON DUPLICATE KEY UPDATE
    title = new.title,
    statement_text = new.statement_text,
    input_description = new.input_description,
    output_description = new.output_description,
    public_samples_json = new.public_samples_json,
    status = new.status;

INSERT INTO problem_judge_version (
    id,
    problem_id,
    version_no,
    time_limit_ms,
    memory_limit_mb,
    output_limit_bytes,
    comparison_rule_version,
    sandbox_policy_version,
    java_image_digest,
    test_dataset_sha256
)
VALUES (
    1,
    1,
    1,
    2000,
    256,
    1048576,
    'trim-trailing-whitespace-v1',
    'm0-v1',
    'eclipse-temurin:21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438',
    '37881a92ca996970e09475fdb29435b9bc13ae1501fa118e5fd9afd47e561adf'
) AS new
ON DUPLICATE KEY UPDATE
    time_limit_ms = new.time_limit_ms,
    memory_limit_mb = new.memory_limit_mb,
    output_limit_bytes = new.output_limit_bytes,
    comparison_rule_version = new.comparison_rule_version,
    sandbox_policy_version = new.sandbox_policy_version,
    java_image_digest = new.java_image_digest,
    test_dataset_sha256 = new.test_dataset_sha256;

UPDATE problem
SET current_judge_version_id = 1
WHERE id = 1;

INSERT INTO problem_test_case (
    id,
    judge_version_id,
    ordinal,
    input_data_gzip,
    expected_output_gzip,
    input_size_bytes,
    output_size_bytes,
    input_sha256,
    output_sha256
)
VALUES
    (
        1,
        1,
        1,
        UNHEX('1F8B080000000000020A335430E2020057BB3B5C04000000'),
        UNHEX('1F8B080000000000020A33E60200D19E675502000000'),
        4,
        2,
        'f251ddc12234e0da8d3b778bd0f7463fb477f16f47757f5617dc8b4ff4d4f14a',
        '1121cfccd5913f0a63fec40a6ffd44ea64f9dc135c66634ba001d10bcf4302a2'
    ),
    (
        2,
        1,
        2,
        UNHEX('1F8B080000000000020AD33551B0E402001A51265605000000'),
        UNHEX('1F8B080000000000020A33E5020057393D0302000000'),
        5,
        2,
        'cfb54cf575309eaee1116780da5f3cf2e4bf123d2e482cc55c4da4ad166f4e48',
        'f0b5c2c2211c8d67ed15e75e656c7862d086e9245420892a7de62cd9ec582a06'
    ),
    (
        3,
        1,
        3,
        UNHEX('1F8B080000000000020A3332343137B1303633315730E40200293184BC0D000000'),
        UNHEX('1F8B080000000000020A3332343137B1303633B1E002008E1889330B000000'),
        13,
        11,
        'cd8795cb0610cab6d1323fbf2ccee015a8ed2e1b45783c9f2d86484fc90e8edd',
        '604fec38960d7c5bd3bd6c06ad8f4b42eac0bb30ea02d43518d0f9f11d5e131e'
    ) AS new
ON DUPLICATE KEY UPDATE
    input_data_gzip = new.input_data_gzip,
    expected_output_gzip = new.expected_output_gzip,
    input_size_bytes = new.input_size_bytes,
    output_size_bytes = new.output_size_bytes,
    input_sha256 = new.input_sha256,
    output_sha256 = new.output_sha256;
