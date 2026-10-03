/*
 * Copyright 2026 池也
 * SPDX-License-Identifier: Apache-2.0
 */
package com.forgeoj.api.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class ProblemLibraryMigrationIntegrationTests {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12"
                    + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("forgeoj").withUsername("bootstrap")
            .withPassword("bootstrap-test-secret")
            .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),
                    "/docker-entrypoint-initdb.d/01-users.sql");

    @Test
    void upgradingV6AddsOnlyNullableMetadataAndPreservesAllExistingBusinessFacts() throws Exception {
        assertThat(flyway("6").migrate().migrationsExecuted).isEqualTo(6);
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), "forgeoj_migrator", "m0-migrator-test-secret");
        JdbcTemplate db = new JdbcTemplate(source);
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/devdata/R__seed_m0_development_data.sql"));
        }
        seedFacts(db);
        Map<String, List<Map<String, Object>>> before = facts(db);
        Flyway upgrade = flyway("7");
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(facts(db)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM problem WHERE difficulty IS NOT NULL", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM problem_tag", Integer.class)).isZero();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
        assertThat(facts(db)).isEqualTo(before);
        // MySQL hides other users' grant rows from this schema-scoped migrator.
        // Inspect each account's own visible privileges without widening test grants.
        JdbcTemplate api = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), "forgeoj_api", "m0-api-test-secret"));
        assertThat(api.queryForList("""
                SELECT PRIVILEGE_TYPE FROM information_schema.TABLE_PRIVILEGES
                WHERE TABLE_SCHEMA='forgeoj' AND TABLE_NAME='problem_tag'
                ORDER BY PRIVILEGE_TYPE
                """, String.class)).containsExactly("SELECT");
        JdbcTemplate worker = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), "forgeoj_worker", "m0-worker-test-secret"));
        assertThat(worker.queryForList("""
                SELECT PRIVILEGE_TYPE FROM information_schema.TABLE_PRIVILEGES
                WHERE TABLE_SCHEMA='forgeoj' AND TABLE_NAME='problem_tag'
                """, String.class)).isEmpty();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(MYSQL.getJdbcUrl(), "forgeoj_migrator", "m0-migrator-test-secret")
                .locations("classpath:db/migration").target(target).load();
    }

    private static Map<String, List<Map<String, Object>>> facts(JdbcTemplate db) {
        Map<String, List<Map<String, Object>>> facts = new LinkedHashMap<>();
        for (String table : List.of("user_account", "user_judge_quota_lock", "login_session", "refresh_token",
                "account_action_token", "problem_judge_version", "problem_test_case", "submission", "judge_task",
                "judge_task_attempt", "outbox_event")) {
            // Deterministic single-column ordering includes digest-keyed and quota tables.
            facts.put(table, db.queryForList("SELECT * FROM " + table + " ORDER BY 1").stream().map(row -> {
                Map<String, Object> normalized = new LinkedHashMap<>();
                row.forEach((key, value) -> normalized.put(key, value instanceof byte[] bytes
                        ? java.util.HexFormat.of().formatHex(bytes) : value));
                return normalized;
            }).toList());
        }
        facts.put("problem", db.queryForList("""
                SELECT id, slug, title, statement_text, input_description, output_description, public_samples_json,
                    status, current_judge_version_id, created_at, updated_at FROM problem ORDER BY id
                """));
        return facts;
    }

    private static void seedFacts(JdbcTemplate db) {
        db.update("""
                UPDATE user_account SET email='library-upgrade@example.test',
                    email_verified_at='2026-09-30 12:00:00.123456', nickname='保留用户' WHERE id=1
                """);
        db.update("UPDATE user_judge_quota_lock SET lock_version=9 WHERE user_id=1");
        db.update("""
                INSERT INTO login_session (id, user_id, expires_at)
                VALUES ('10000000-0000-4000-8000-000000000001', 1, '2030-01-01 12:00:00')
                """);
        db.update("INSERT INTO refresh_token (token_sha256, session_id) VALUES (?, '10000000-0000-4000-8000-000000000001')",
                "a".repeat(64));
        db.update("""
                INSERT INTO account_action_token (token_sha256, user_id, purpose, target_email, expires_at)
                VALUES (?, 1, 'RESET_PASSWORD', 'library-upgrade@example.test', '2030-01-01 12:00:00')
                """, "b".repeat(64));
        db.update("""
                INSERT INTO submission (id, user_id, problem_id, judge_version_id, client_request_id, language,
                    source_code, source_sha256, time_limit_ms, memory_limit_mb, output_limit_bytes,
                    comparison_rule_version, sandbox_policy_version, java_image_digest, test_dataset_sha256,
                    processing_status, verdict, status_version, started_at, finished_at)
                SELECT '20000000-0000-4000-8000-000000000001', 1, problem_id, id,
                    '30000000-0000-4000-8000-000000000001', 'JAVA_21', 'public class Main {}', ?,
                    time_limit_ms, memory_limit_mb, output_limit_bytes, comparison_rule_version, sandbox_policy_version,
                    java_image_digest, test_dataset_sha256, 'FINISHED', 'AC', 2,
                    '2026-09-30 12:00:01.123456', '2026-09-30 12:00:02.123456'
                FROM problem_judge_version WHERE id=1
                """, "c".repeat(64));
        db.update("""
                INSERT INTO judge_task (id, submission_id, task_type, contract_version, task_status, status_version,
                    attempt_count, max_attempts, started_at, finished_at)
                VALUES ('40000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001',
                    'JUDGE_SUBMISSION', 1, 'FINISHED', 2, 1, 3, '2026-09-30 12:00:01.123456',
                    '2026-09-30 12:00:02.123456')
                """);
        db.update("""
                INSERT INTO judge_task_attempt (id, judge_task_id, attempt_no, lease_token, worker_id, attempt_status,
                    lease_expires_at, finished_at)
                VALUES ('50000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000001', 1,
                    '60000000-0000-4000-8000-000000000001', 'library-upgrade-worker', 'SUCCEEDED',
                    '2026-09-30 12:00:31.123456', '2026-09-30 12:00:02.123456')
                """);
        db.update("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, contract_version, payload,
                    published_at, publish_attempts)
                VALUES ('70000000-0000-4000-8000-000000000001', 'JUDGE_TASK',
                    '40000000-0000-4000-8000-000000000001', 'JUDGE_TASK_CREATED', 1,
                    JSON_OBJECT('contractVersion', 1, 'taskType', 'JUDGE_SUBMISSION',
                        'taskId', '40000000-0000-4000-8000-000000000001',
                        'submissionId', '20000000-0000-4000-8000-000000000001'),
                    '2026-09-30 12:00:00.123456', 1)
                """);
    }
}
