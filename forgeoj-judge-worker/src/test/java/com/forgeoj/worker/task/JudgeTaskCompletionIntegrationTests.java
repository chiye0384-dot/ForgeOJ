package com.forgeoj.worker.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeoj.worker.messaging.JudgeTaskMessage;
import com.forgeoj.worker.sandbox.SandboxExecutionResult;
import com.forgeoj.worker.sandbox.SandboxOutcome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
class JudgeTaskCompletionIntegrationTests {

    private static final String MYSQL_IMAGE =
            "container-registry.oracle.com/mysql/community-server:8.4.12"
                    + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be";
    private static final String MIGRATOR_PASSWORD = "m0-migrator-test-secret";
    private static final String WORKER_PASSWORD = "m0-worker-test-secret";

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(
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
            executeScript(connection, "db/migration/V1__create_m0_core_schema.sql");
            executeScript(connection, "db/migration/V2__allow_ole_verdict.sql");
            executeScript(connection, "db/migration/V3__add_m1_attempt_lease_and_retry.sql");
            executeScript(connection, "db/devdata/R__seed_m0_development_data.sql");
        }
    }

    @Autowired
    private JudgeTaskCompletionService completionService;

    @Autowired
    private DataSource workerDataSource;

    @BeforeEach
    void resetTasks() {
        JdbcTemplate migrator = migrator();
        migrator.update("DELETE FROM judge_task_attempt");
        migrator.update("DELETE FROM outbox_event");
        migrator.update("DELETE FROM judge_task");
        migrator.update("DELETE FROM submission");
    }

    @ParameterizedTest
    @MethodSource("userOutcomes")
    void atomicallyPersistsEveryM0UserVerdict(SandboxOutcome outcome, String verdict) {
        TaskIds ids = insertRunningTask();
        String diagnostic = outcome == SandboxOutcome.COMPILE_ERROR ? "Main.java: compiler detail" : "";

        completionService.finish(
                claim(ids), new SandboxExecutionResult(outcome, diagnostic));

        java.util.Map<String, Object> state = state(ids);
        assertThat(state)
                .containsEntry("task_status", "FINISHED")
                .containsEntry("processing_status", "FINISHED")
                .containsEntry("attempt_status", "SUCCEEDED")
                .containsEntry("verdict", verdict);
        assertThat(((Number) state.get("task_version")).longValue()).isEqualTo(2L);
        assertThat(((Number) state.get("submission_version")).longValue()).isEqualTo(2L);
        assertThat(state.get("task_finished_at")).isNotNull();
        assertThat(state.get("submission_finished_at")).isNotNull();
        if (outcome == SandboxOutcome.COMPILE_ERROR) {
            assertThat(state.get("diagnostic_message")).isEqualTo(diagnostic);
        } else {
            assertThat(state.get("diagnostic_message")).isNull();
        }
    }

    @Test
    void atomicallySchedulesRetryForPlatformFailureWithoutAUserVerdict() {
        TaskIds ids = insertRunningTask();

        completionService.recordPlatformFailure(claim(ids));

        java.util.Map<String, Object> state = state(ids);
        assertThat(state)
                .containsEntry("task_status", "RETRYING")
                .containsEntry("processing_status", "RETRYING")
                .containsEntry("attempt_status", "RETRYABLE_FAILURE");
        assertThat(state.get("verdict")).isNull();
        assertThat(state.get("diagnostic_message")).isNull();
        assertThat(state.get("next_attempt_at")).isNotNull();
        assertThat(((Number) state.get("task_version")).longValue()).isEqualTo(2L);
        assertThat(((Number) state.get("submission_version")).longValue()).isEqualTo(2L);
        assertThat(outboxState(ids))
                .containsEntry("event_type", "JUDGE_TASK_QUEUED")
                .containsEntry("sequence_no", 1L);
    }

    @Test
    void atomicallyDeadLettersPlatformFailureAtAttemptLimit() {
        TaskIds ids = insertRunningTask(3, 3);

        completionService.recordPlatformFailure(claim(ids));

        java.util.Map<String, Object> state = state(ids);
        assertThat(state)
                .containsEntry("task_status", "DEAD_LETTER")
                .containsEntry("processing_status", "SYSTEM_ERROR")
                .containsEntry("attempt_status", "DEAD_LETTERED");
        assertThat(state.get("verdict")).isNull();
        assertThat(state.get("diagnostic_message")).isEqualTo("Judging infrastructure failed");
        assertThat(state.get("next_attempt_at")).isNull();
        assertThat(outboxState(ids))
                .containsEntry("event_type", "JUDGE_TASK_DEAD_LETTERED")
                .containsEntry("sequence_no", 3L);
    }

    @Test
    void atomicallyDeadLettersAnUnrecoverableFailureWithoutWaitingForAttemptLimit() {
        TaskIds ids = insertRunningTask();

        completionService.recordUnrecoverablePlatformFailure(claim(ids));

        assertThat(state(ids))
                .containsEntry("task_status", "DEAD_LETTER")
                .containsEntry("processing_status", "SYSTEM_ERROR")
                .containsEntry("attempt_status", "DEAD_LETTERED")
                .containsEntry("last_failure_code", "SNAPSHOT_INVALID");
        assertThat(outboxState(ids))
                .containsEntry("event_type", "JUDGE_TASK_DEAD_LETTERED")
                .containsEntry("sequence_no", 1L);
        assertThat(
                        migrator()
                                .queryForObject(
                                        "SELECT failure_code FROM judge_task_attempt WHERE id = ?",
                                        String.class,
                                        ids.attemptId()))
                .isEqualTo("SNAPSHOT_INVALID");
    }

    @Test
    void rollsBackBothTerminalWritesWhenSubmissionUpdateIsDenied() {
        TaskIds ids = insertRunningTask();
        JdbcTemplate migrator = migrator();
        migrator.execute("REVOKE UPDATE ON forgeoj.submission FROM 'forgeoj_worker'@'%'");

        try {
            assertThatThrownBy(
                            () ->
                                    completionService.finish(
                                            claim(ids),
                                            SandboxExecutionResult.of(
                                                    SandboxOutcome.ACCEPTED)))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            migrator.execute(
                    "GRANT SELECT, UPDATE ON forgeoj.submission TO 'forgeoj_worker'@'%'");
        }

        java.util.Map<String, Object> state = state(ids);
        assertThat(state)
                .containsEntry("task_status", "RUNNING")
                .containsEntry("processing_status", "RUNNING");
        assertThat(((Number) state.get("task_version")).longValue()).isEqualTo(1L);
        assertThat(((Number) state.get("submission_version")).longValue()).isEqualTo(1L);
    }

    @Test
    void mismatchedSubmissionFailsClosedWithoutChangingEitherRecord() {
        TaskIds first = insertRunningTask();
        TaskIds second = insertRunningTask();

        assertThatThrownBy(
                        () ->
                                completionService.finish(
                                        new ClaimedJudgeTask(
                                                new JudgeTaskMessage(
                                                        first.taskId(),
                                                        second.submissionId(),
                                                        "JUDGE_SUBMISSION",
                                                        1),
                                                first.attemptId(),
                                                1,
                                                first.leaseToken(),
                                                "completion-test-worker"),
                                        SandboxExecutionResult.of(SandboxOutcome.ACCEPTED)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(state(first))
                .containsEntry("task_status", "RUNNING")
                .containsEntry("processing_status", "RUNNING");
    }

    @Test
    void staleLeaseCannotOverwriteTheCurrentRunningAttempt() {
        TaskIds ids = insertRunningTask();
        ClaimedJudgeTask stale =
                new ClaimedJudgeTask(
                        message(ids),
                        ids.attemptId(),
                        1,
                        UUID.randomUUID().toString(),
                        "stale-worker");

        assertThatThrownBy(
                        () ->
                                completionService.finish(
                                        stale,
                                        SandboxExecutionResult.of(SandboxOutcome.ACCEPTED)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(state(ids))
                .containsEntry("task_status", "RUNNING")
                .containsEntry("processing_status", "RUNNING")
                .containsEntry("attempt_status", "RUNNING");
    }

    private static Stream<Arguments> userOutcomes() {
        return Stream.of(
                Arguments.of(SandboxOutcome.ACCEPTED, "AC"),
                Arguments.of(SandboxOutcome.WRONG_ANSWER, "WA"),
                Arguments.of(SandboxOutcome.COMPILE_ERROR, "CE"),
                Arguments.of(SandboxOutcome.RUNTIME_ERROR, "RE"),
                Arguments.of(SandboxOutcome.TIME_LIMIT_EXCEEDED, "TLE"),
                Arguments.of(SandboxOutcome.OUTPUT_LIMIT_EXCEEDED, "OLE"));
    }

    private TaskIds insertRunningTask() {
        return insertRunningTask(1, 3);
    }

    private TaskIds insertRunningTask(int attemptNo, int maxAttempts) {
        String submissionId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        String leaseToken = UUID.randomUUID().toString();
        JdbcTemplate migrator = migrator();
        migrator.update(
                """
                INSERT INTO submission (
                    id, user_id, problem_id, judge_version_id, client_request_id,
                    language, source_code, source_sha256, time_limit_ms,
                    memory_limit_mb, output_limit_bytes, comparison_rule_version,
                    sandbox_policy_version, java_image_digest, test_dataset_sha256,
                    processing_status, status_version, started_at
                ) VALUES (?, 1, 1, 1, ?, 'JAVA_21', 'public class Main {}',
                    REPEAT('a', 64), 2000, 192, 65536,
                    'trim-trailing-whitespace-v1', 'm0-v1',
                    'eclipse-temurin:test', REPEAT('b', 64),
                    'RUNNING', 1, CURRENT_TIMESTAMP(6))
                """,
                submissionId,
                UUID.randomUUID().toString());
        migrator.update(
                """
                INSERT INTO judge_task (
                    id, submission_id, task_type, contract_version, task_status,
                    status_version, attempt_count, max_attempts, lease_owner, lease_token,
                    lease_expires_at, started_at
                ) VALUES (?, ?, 'JUDGE_SUBMISSION', 1, 'RUNNING', 1, ?, ?,
                    'completion-test-worker', ?, CURRENT_TIMESTAMP(6) + INTERVAL 30 SECOND,
                    CURRENT_TIMESTAMP(6))
                """,
                taskId,
                submissionId,
                attemptNo,
                maxAttempts,
                leaseToken);
        migrator.update(
                """
                INSERT INTO judge_task_attempt (
                    id, judge_task_id, attempt_no, lease_token, worker_id,
                    attempt_status, lease_expires_at
                ) VALUES (?, ?, ?, ?, 'completion-test-worker', 'RUNNING',
                    CURRENT_TIMESTAMP(6) + INTERVAL 30 SECOND)
                """,
                attemptId,
                taskId,
                attemptNo,
                leaseToken);
        return new TaskIds(taskId, submissionId, attemptId, leaseToken, attemptNo);
    }

    private java.util.Map<String, Object> state(TaskIds ids) {
        return new JdbcTemplate(workerDataSource)
                .queryForMap(
                        """
                        SELECT jt.task_status,
                               jt.status_version AS task_version,
                               jt.finished_at AS task_finished_at,
                               jt.next_attempt_at,
                               jt.last_failure_code,
                               a.attempt_status,
                               s.processing_status,
                               s.verdict,
                               s.status_version AS submission_version,
                               s.diagnostic_message,
                               s.finished_at AS submission_finished_at
                        FROM judge_task jt
                        JOIN judge_task_attempt a ON a.judge_task_id = jt.id
                        JOIN submission s ON s.id = jt.submission_id
                        WHERE jt.id = ? AND s.id = ?
                        """,
                        ids.taskId(),
                        ids.submissionId());
    }

    private java.util.Map<String, Object> outboxState(TaskIds ids) {
        return migrator()
                .queryForMap(
                        """
                        SELECT event_type, sequence_no
                        FROM outbox_event
                        WHERE aggregate_id = ?
                        """,
                        ids.taskId());
    }

    private JudgeTaskMessage message(TaskIds ids) {
        return new JudgeTaskMessage(
                ids.taskId(), ids.submissionId(), "JUDGE_SUBMISSION", 1);
    }

    private ClaimedJudgeTask claim(TaskIds ids) {
        return new ClaimedJudgeTask(
                message(ids),
                ids.attemptId(),
                ids.attemptNo(),
                ids.leaseToken(),
                "completion-test-worker");
    }

    private JdbcTemplate migrator() {
        return new JdbcTemplate(
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), "forgeoj_migrator", MIGRATOR_PASSWORD));
    }

    private static void executeScript(Connection connection, String relativePath) {
        ScriptUtils.executeSqlScript(
                connection,
                new FileSystemResource(repositoryFile("forgeoj-api/src/main/resources/" + relativePath)));
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

    private record TaskIds(
            String taskId,
            String submissionId,
            String attemptId,
            String leaseToken,
            int attemptNo) {}
}
