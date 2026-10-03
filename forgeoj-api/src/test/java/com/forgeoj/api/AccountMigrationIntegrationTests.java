/*
 * Copyright 2026 池也
 * SPDX-License-Identifier: Apache-2.0
 */
package com.forgeoj.api;

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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class AccountMigrationIntegrationTests {

    private static final String MYSQL_IMAGE =
            "container-registry.oracle.com/mysql/community-server:8.4.12"
                    + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be";
    private static final String MIGRATOR_PASSWORD = "m0-migrator-test-secret";
    private static final String LEGACY_PASSWORD = "forgeoj-dev-only";
    private static final String LEGACY_HASH =
            "$2a$10$slWnrjf2WJd.j/4Fnc1m..7QjwDgtyAlJ4OrLRzEjhAN0g7zkuyCi";
    private static final String LEGACY_USERNAME =
            "Legacy-Account-Name-With-Hyphen-Longer-Than-32";
    private static final String FINISHED_SUBMISSION = "10000000-0000-4000-8000-000000000001";
    private static final String WAITING_SUBMISSION = "10000000-0000-4000-8000-000000000002";
    private static final String FINISHED_TASK = "20000000-0000-4000-8000-000000000001";
    private static final String WAITING_TASK = "20000000-0000-4000-8000-000000000002";

    @Container
    static final MySQLContainer MYSQL =
            new com.forgeoj.api.testinfra.DirectMySQLContainer(
                            DockerImageName.parse(MYSQL_IMAGE)
                                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("forgeoj")
                    .withUsername("bootstrap")
                    .withPassword("bootstrap-test-secret")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("mysql/init-test-users.sql"),
                            "/docker-entrypoint-initdb.d/01-init-test-users.sql");

    @Test
    void upgradingV5PreservesLegacyAccountsQuotasAndHistoricalJudgingFacts() throws Exception {
        assertThat(flyway("5").migrate().migrationsExecuted).isEqualTo(5);
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), "forgeoj_migrator", MIGRATOR_PASSWORD);
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/devdata/R__seed_m0_development_data.sql"));
        }
        seedLegacyFacts(database);

        var accountsBefore =
                database.queryForList(
                        "SELECT id, username, password_hash, status, created_at FROM user_account ORDER BY id");
        var judgingFactsBefore = judgingFacts(database);

        Flyway upgrade = flyway("6");
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(
                        database.queryForList(
                                "SELECT id, username, password_hash, status, created_at FROM user_account ORDER BY id"))
                .isEqualTo(accountsBefore);
        assertThat(judgingFacts(database)).isEqualTo(judgingFactsBefore);

        assertThat(database.queryForList("SELECT id FROM user_account ORDER BY id", Long.class))
                .containsExactly(1L, 12L, 13L);
        assertThat(
                        database.queryForObject(
                                "SELECT COUNT(*) FROM user_account WHERE email IS NULL AND email_verified_at IS NULL AND nickname IS NULL",
                                Integer.class))
                .isEqualTo(3);
        assertThat(
                        database.queryForObject(
                                "SELECT id FROM user_account WHERE username = ? AND status = 'ACTIVE'",
                                Long.class,
                                LEGACY_USERNAME.toLowerCase(java.util.Locale.ROOT)))
                .isEqualTo(13L);
        String preservedHash =
                database.queryForObject(
                        "SELECT password_hash FROM user_account WHERE id = 13", String.class);
        assertThat(preservedHash).isEqualTo(LEGACY_HASH);
        assertThat(new BCryptPasswordEncoder().matches(LEGACY_PASSWORD, preservedHash)).isTrue();

        for (String table : List.of("login_session", "refresh_token", "account_action_token")) {
            assertThat(database.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                    .as("V6 must not fabricate old-account sessions or email verification: %s", table)
                    .isZero();
        }
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
        assertThat(judgingFacts(database)).isEqualTo(judgingFactsBefore);
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), "forgeoj_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static Map<String, List<Map<String, Object>>> judgingFacts(JdbcTemplate database) {
        Map<String, List<Map<String, Object>>> facts = new LinkedHashMap<>();
        for (String table :
                List.of("submission", "judge_task", "judge_task_attempt", "outbox_event")) {
            facts.put(table, database.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        facts.put(
                "user_judge_quota_lock",
                database.queryForList("SELECT * FROM user_judge_quota_lock ORDER BY user_id"));
        return facts;
    }

    private static void seedLegacyFacts(JdbcTemplate database) {
        database.update(
                """
                INSERT INTO user_account (id, username, password_hash, status, created_at)
                VALUES (12, 'disabled-before-accounts', ?, 'DISABLED', '2026-09-01 12:00:00.123456'),
                       (13, ?, ?, 'ACTIVE', '2026-09-02 12:00:00.654321')
                """,
                LEGACY_HASH,
                LEGACY_USERNAME,
                LEGACY_HASH);
        database.update(
                "INSERT INTO user_judge_quota_lock (user_id, lock_version) VALUES (12, 3), (13, 9)");
        database.update("UPDATE user_judge_quota_lock SET lock_version = 7 WHERE user_id = 1");
        insertSubmission(database, FINISHED_SUBMISSION, 1, "FINISHED", "SECURITY_VIOLATION");
        insertSubmission(database, WAITING_SUBMISSION, 13, "RUNNING", null);
        database.update(
                """
                INSERT INTO judge_task
                    (id, submission_id, task_type, contract_version, task_status, status_version,
                     attempt_count, max_attempts, created_at, started_at, finished_at)
                VALUES (?, ?, 'JUDGE_SUBMISSION', 1, 'FINISHED', 2, 1, 3,
                        '2026-09-29 10:00:00.123456', '2026-09-29 10:00:01.123456',
                        '2026-09-29 10:00:02.123456')
                """,
                FINISHED_TASK,
                FINISHED_SUBMISSION);
        database.update(
                """
                INSERT INTO judge_task
                    (id, submission_id, task_type, contract_version, task_status, status_version,
                     attempt_count, max_attempts, next_attempt_at, last_failure_code,
                     created_at, started_at)
                VALUES (?, ?, 'JUDGE_SUBMISSION', 1, 'WAITING_RETRY', 3, 1, 3,
                        '2026-10-02 12:00:00.123456', 'QUOTA_DEFERRED',
                        '2026-09-30 10:00:00.123456', '2026-09-30 10:00:01.123456')
                """,
                WAITING_TASK,
                WAITING_SUBMISSION);
        database.update(
                """
                INSERT INTO judge_task_attempt
                    (id, judge_task_id, attempt_no, lease_token, worker_id, attempt_status,
                     started_at, heartbeat_at, lease_expires_at, finished_at)
                VALUES ('30000000-0000-4000-8000-000000000002', ?, 1,
                        '40000000-0000-4000-8000-000000000002', 'legacy-worker', 'SUCCEEDED',
                        '2026-09-29 10:00:01.123456', '2026-09-29 10:00:01.123456',
                        '2026-09-29 10:00:31.123456', '2026-09-29 10:00:02.123456')
                """,
                FINISHED_TASK);
        database.update(
                """
                INSERT INTO judge_task_attempt
                    (id, judge_task_id, attempt_no, lease_token, worker_id, attempt_status,
                     started_at, heartbeat_at, lease_expires_at, finished_at, failure_code)
                VALUES ('30000000-0000-4000-8000-000000000001', ?, 1,
                        '40000000-0000-4000-8000-000000000001', 'legacy-worker', 'RETRYABLE_FAILURE',
                        '2026-09-30 10:00:01.123456', '2026-09-30 10:00:01.123456',
                        '2026-09-30 10:00:31.123456', '2026-09-30 10:00:02.123456', 'QUOTA_DEFERRED')
                """,
                WAITING_TASK);
        database.update(
                """
                INSERT INTO outbox_event
                    (id, aggregate_type, aggregate_id, event_type, contract_version, sequence_no,
                     payload, publish_attempts, next_attempt_at, created_at)
                VALUES ('50000000-0000-4000-8000-000000000001', 'JUDGE_TASK', ?,
                        'JUDGE_TASK_CREATED', 1, 0,
                        JSON_OBJECT('contractVersion', 1, 'taskType', 'JUDGE_SUBMISSION',
                                    'taskId', ?, 'submissionId', ?),
                        2, '2026-10-02 12:00:00.123456', '2026-09-30 10:00:00.123456')
                """,
                WAITING_TASK,
                WAITING_TASK,
                WAITING_SUBMISSION);
    }

    private static void insertSubmission(
            JdbcTemplate database, String id, long userId, String processingStatus, String verdict) {
        database.update(
                """
                INSERT INTO submission
                    (id, user_id, problem_id, judge_version_id, client_request_id, language,
                     source_code, source_sha256, time_limit_ms, memory_limit_mb, output_limit_bytes,
                     comparison_rule_version, sandbox_policy_version, java_image_digest,
                     test_dataset_sha256, processing_status, verdict, status_version,
                     created_at, started_at, finished_at)
                SELECT ?, ?, problem_id, id, ?, 'JAVA_21',
                       'public class Main { public static void main(String[] args) {} }',
                       REPEAT('a', 64), time_limit_ms, memory_limit_mb, output_limit_bytes,
                       comparison_rule_version, sandbox_policy_version, java_image_digest,
                       test_dataset_sha256, ?, ?, IF(? = 'FINISHED', 2, 3),
                       '2026-09-29 10:00:00.123456', '2026-09-29 10:00:01.123456',
                       IF(? = 'FINISHED', '2026-09-29 10:00:02.123456', NULL)
                FROM problem_judge_version WHERE id = 1
                """,
                id,
                userId,
                id,
                processingStatus,
                verdict,
                processingStatus,
                processingStatus);
    }
}
