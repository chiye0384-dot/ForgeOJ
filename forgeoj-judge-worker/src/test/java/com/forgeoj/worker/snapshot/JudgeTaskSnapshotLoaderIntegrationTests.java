package com.forgeoj.worker.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeoj.worker.messaging.JudgeTaskMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Testcontainers
@SpringBootTest
class JudgeTaskSnapshotLoaderIntegrationTests {

    private static final String MYSQL_IMAGE =
            "container-registry.oracle.com/mysql/community-server:8.4.12"
                    + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be";
    private static final String MIGRATOR_PASSWORD = "m0-migrator-test-secret";
    private static final String WORKER_PASSWORD = "m0-worker-test-secret";
    private static final String SOURCE =
            "public class Main { public static void main(String[] args) {} }";

    @Container
    static final MySQLContainer MYSQL =
            new com.forgeoj.worker.testinfra.DirectMySQLContainer(
                            DockerImageName.parse(MYSQL_IMAGE)
                                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("forgeoj")
                    .withUsername("bootstrap")
                    .withPassword("bootstrap-test-secret")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(
                                    repositoryFile(
                                            "forgeoj-api/src/test/resources/mysql/init-test-users.sql")),
                            "/docker-entrypoint-initdb.d/01-init-test-users.sql");

    @DynamicPropertySource
    static void runtimeProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "forgeoj_worker");
        registry.add("spring.datasource.password", () -> WORKER_PASSWORD);
    }

    @BeforeAll
    static void initializeSchema() throws Exception {
        try (Connection connection =
                DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), "forgeoj_migrator", MIGRATOR_PASSWORD)) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new FileSystemResource(
                            repositoryFile(
                                    "forgeoj-api/src/main/resources/db/migration/"
                                            + "V1__create_m0_core_schema.sql")));
            ScriptUtils.executeSqlScript(
                    connection,
                    new FileSystemResource(
                            repositoryFile(
                                    "forgeoj-api/src/main/resources/db/migration/"
                                            + "V2__allow_ole_verdict.sql")));
            ScriptUtils.executeSqlScript(
                    connection,
                    new FileSystemResource(
                            repositoryFile(
                                    "forgeoj-api/src/main/resources/db/migration/"
                                            + "V3__add_m1_attempt_lease_and_retry.sql")));
            ScriptUtils.executeSqlScript(
                    connection,
                    new FileSystemResource(
                            repositoryFile(
                                    "forgeoj-api/src/main/resources/db/migration/"
                                            + "V4__add_user_judge_quota_lock.sql")));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(repositoryFile(
                    "forgeoj-api/src/main/resources/db/migration/V5__widen_submission_verdict.sql")));
            com.forgeoj.worker.testinfra.ContentTestSchema.afterV5(connection,repositoryFile("forgeoj-api/src/main/resources/db/migration/V6__ordinary_accounts.sql").getParent());
            ScriptUtils.executeSqlScript(
                    connection,
                    new FileSystemResource(
                            repositoryFile(
                                    "forgeoj-api/src/main/resources/db/devdata/"
                                            + "R__seed_m0_development_data.sql")));
        }
    }

    @Autowired
    private JudgeTaskSnapshotLoader loader;

    @Test
    void loadsSubmissionSnapshotAndOrderedHiddenTestsOnlyForMatchingRunningTask() {
        TaskIds ids = insertRunningTask();

        JudgeTaskSnapshot snapshot = loader.load(message(ids));

        assertThat(snapshot.taskId()).isEqualTo(ids.taskId());
        assertThat(snapshot.submissionId()).isEqualTo(ids.submissionId());
        assertThat(snapshot.language()).isEqualTo("JAVA_21");
        assertThat(snapshot.sourceCode()).isEqualTo(SOURCE);
        assertThat(snapshot.sourceSha256())
                .isEqualTo(sha256(SOURCE.getBytes(StandardCharsets.UTF_8)));
        assertThat(snapshot.timeLimitMs()).isEqualTo(1500);
        assertThat(snapshot.memoryLimitMb()).isEqualTo(192);
        assertThat(snapshot.outputLimitBytes()).isEqualTo(65536);
        assertThat(snapshot.testCases()).extracting(JudgeTestCase::ordinal).containsExactly(1, 2, 3);
        assertThat(new String(snapshot.testCases().getFirst().input(), StandardCharsets.UTF_8))
                .isEqualTo("1 2\n");
        assertThat(
                        new String(
                                snapshot.testCases().getFirst().expectedOutput(),
                                StandardCharsets.UTF_8))
                .isEqualTo("3\n");
    }

    @Test
    void currentVersionAdvanceDoesNotRedirectExistingTaskSnapshotOrHiddenTests() {
        TaskIds ids = insertRunningTask();
        JudgeTaskSnapshot before = loader.load(message(ids));
        JdbcTemplate database = migrator();
        database.update("""
                INSERT INTO problem_judge_version
                    (problem_id, version_no, time_limit_ms, memory_limit_mb, output_limit_bytes,
                     comparison_rule_version, sandbox_policy_version, java_image_digest, test_dataset_sha256)
                SELECT problem_id, 2, 999, 128, 4096, comparison_rule_version,
                       sandbox_policy_version, java_image_digest, REPEAT('f', 64)
                FROM problem_judge_version WHERE id = 1
                """);
        long nextVersion = database.queryForObject(
                "SELECT id FROM problem_judge_version WHERE problem_id = 1 AND version_no = 2", Long.class);
        try {
            database.update("UPDATE problem SET current_judge_version_id = ? WHERE id = 1", nextVersion);
            JudgeTaskSnapshot after = loader.load(message(ids));
            assertThat(after).usingRecursiveComparison().isEqualTo(before);
            assertThat(after.judgeVersionId()).isEqualTo(1);
            assertThat(after.testCases()).hasSize(3);
            assertThat(after.timeLimitMs()).isEqualTo(1500);
        } finally {
            database.update("UPDATE problem SET current_judge_version_id = 1 WHERE id = 1");
            database.update("DELETE FROM problem_judge_version WHERE id = ?", nextVersion);
        }
    }

    @Test
    void refusesToReadHiddenTestsForQueuedOrMismatchedTask() {
        TaskIds ids = insertRunningTask();
        migrator().update(
                "UPDATE judge_task SET task_status = 'QUEUED', started_at = NULL WHERE id = ?",
                ids.taskId());

        assertThatThrownBy(() -> loader.load(message(ids)))
                .isInstanceOf(JudgeTaskSnapshotException.class)
                .hasMessageContaining("running task");

        assertThatThrownBy(
                        () ->
                                loader.load(
                                        new JudgeTaskMessage(
                                                ids.taskId(),
                                                UUID.randomUUID().toString(),
                                                "JUDGE_SUBMISSION",
                                                1)))
                .isInstanceOf(JudgeTaskSnapshotException.class)
                .hasMessageContaining("running task");
    }

    @Test
    void detectsTamperedSourceBeforeReturningHiddenTests() {
        TaskIds ids = insertRunningTask();
        migrator().update(
                "UPDATE submission SET source_code = 'tampered' WHERE id = ?",
                ids.submissionId());

        assertThatThrownBy(() -> loader.load(message(ids)))
                .isInstanceOf(JudgeTaskSnapshotException.class)
                .hasMessageContaining("source");
    }

    @Test
    void detectsTamperedCompressedTestData() {
        TaskIds ids = insertRunningTask();
        migrator().update(
                "UPDATE problem_test_case SET input_data_gzip = X'00' "
                        + "WHERE judge_version_id = 1 AND ordinal = 1");

        try {
            assertThatThrownBy(() -> loader.load(message(ids)))
                    .isInstanceOf(JudgeTaskSnapshotException.class)
                    .hasMessageContaining("test case");
        } finally {
            restoreSeedData();
        }
    }

    private TaskIds insertRunningTask() {
        String submissionId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        migrator().update(
                """
                INSERT INTO submission (
                    id, user_id, problem_id, judge_version_id, client_request_id,
                    language, source_code, source_sha256, time_limit_ms,
                    memory_limit_mb, output_limit_bytes, comparison_rule_version,
                    sandbox_policy_version, java_image_digest, test_dataset_sha256,
                    processing_status, status_version, started_at
                ) VALUES (?, 1, 1, 1, ?, 'JAVA_21', ?, ?, 1500, 192, 65536,
                    'trim-trailing-whitespace-v1', 'm0-v1',
                    'eclipse-temurin:21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438',
                    '37881a92ca996970e09475fdb29435b9bc13ae1501fa118e5fd9afd47e561adf',
                    'RUNNING', 1, CURRENT_TIMESTAMP(6))
                """,
                submissionId,
                UUID.randomUUID().toString(),
                SOURCE,
                sha256(SOURCE.getBytes(StandardCharsets.UTF_8)));
        migrator().update(
                """
                INSERT INTO judge_task (
                    id, submission_id, task_type, contract_version, task_status,
                    status_version, started_at
                ) VALUES (?, ?, 'JUDGE_SUBMISSION', 1, 'RUNNING', 1, CURRENT_TIMESTAMP(6))
                """,
                taskId,
                submissionId);
        return new TaskIds(taskId, submissionId);
    }

    private JudgeTaskMessage message(TaskIds ids) {
        return new JudgeTaskMessage(ids.taskId(), ids.submissionId(), "JUDGE_SUBMISSION", 1);
    }

    private JdbcTemplate migrator() {
        return new JdbcTemplate(
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), "forgeoj_migrator", MIGRATOR_PASSWORD));
    }

    private void restoreSeedData() {
        try (Connection connection =
                DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), "forgeoj_migrator", MIGRATOR_PASSWORD)) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new FileSystemResource(
                            repositoryFile(
                                    "forgeoj-api/src/main/resources/db/devdata/"
                                            + "R__seed_m0_development_data.sql")));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Path repositoryFile(String relativePath) {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            Path candidate = cursor.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Cannot locate repository file: " + relativePath);
    }

    private record TaskIds(String taskId, String submissionId) {}
}
