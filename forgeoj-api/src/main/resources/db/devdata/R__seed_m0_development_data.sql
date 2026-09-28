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
    '75929bbad91d8af5571333e501c7f5067b169e1569fd8f8d79452005ff596667'
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
        UNHEX('1F8B080000000000000A3354304AC8030013DF491205000000'),
        UNHEX('1F8B080000000000000A334EC8030023A59B1603000000'),
        5,
        3,
        'd57f749064a1b26f6c377a62009eb66b724900e896a12c07d762c59274f7a094',
        '746370e1d9ecbc80925bcbde16713be54ce50488d36dedc59f782bca28fae0f9'
    ),
    (
        2,
        1,
        2,
        UNHEX('1F8B080000000000000AD33551B04CC80300D4FF2E1A06000000'),
        UNHEX('1F8B080000000000000A334DC8030091D9161203000000'),
        6,
        3,
        '6ebf6247339ccbffcb8f3836bef8a9179f0583debdf628555dce1f58c52af236',
        '813f63bbea0f79d2987733a7ae9a6efc6c2ddeb696a4d7766af01e41975ae640'
    ),
    (
        3,
        1,
        3,
        UNHEX('1F8B080000000000000A3332343137B13036333157304CC80300A23C14A50E000000'),
        UNHEX('1F8B080000000000000A3332343137B1303633B148C80300C00729ED0C000000'),
        14,
        12,
        'f69195df2b3d5cac2424b9cd214a649c7d997f6843b33852898117d8d2028bba',
        '9361ec906ed9442f3b43d0cff168a9ac0f40beb8de2565acf2949f4cc02cd40f'
    ) AS new
ON DUPLICATE KEY UPDATE
    input_data_gzip = new.input_data_gzip,
    expected_output_gzip = new.expected_output_gzip,
    input_size_bytes = new.input_size_bytes,
    output_size_bytes = new.output_size_bytes,
    input_sha256 = new.input_sha256,
    output_sha256 = new.output_sha256;
