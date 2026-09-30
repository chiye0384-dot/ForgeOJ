package com.forgeoj.worker.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        properties = {
            "forgeoj.worker.consumer.enabled=true",
            "forgeoj.worker.sandbox.enabled=true",
            "spring.rabbitmq.listener.simple.acknowledge-mode=manual",
            "spring.rabbitmq.listener.simple.prefetch=1"
        })
class JudgeTaskExecutionIntegrationTests {

    private static final String MYSQL_IMAGE =
            "container-registry.oracle.com/mysql/community-server:8.4.12"
                    + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be";
    private static final String RABBIT_IMAGE =
            "rabbitmq:4.3.6-management"
                    + "@sha256:cdf40d8cb363d145e377ed88d59696a42386ffe54b30125f10eb128b862eea95";
    private static final String MIGRATOR_PASSWORD = "m0-migrator-test-secret";
    private static final String WORKER_PASSWORD = "m0-worker-test-secret";
    private static final String RABBIT_USER = "forgeoj";
    private static final String RABBIT_PASSWORD = "m0-rabbit-test-secret";
    private static final String OUTPUT_LIMIT_SOURCE =
            "public class Main { public static void main(String[] args) {"
                    + " while (true) { System.out.print(\"0123456789\"); } } }";
    private static final String SIMPLE_SOURCE =
            "public class Main { public static void main(String[] args) {} }";
    private static boolean schemaInitialized;

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

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(
                            DockerImageName.parse(RABBIT_IMAGE)
                                    .asCompatibleSubstituteFor("rabbitmq"))
                    .withAdminUser(RABBIT_USER)
                    .withAdminPassword(RABBIT_PASSWORD)
                    .withEnv("RABBITMQ_DEFAULT_VHOST", "/forgeoj");

    @DynamicPropertySource
    static void runtimeProperties(DynamicPropertyRegistry registry) throws Exception {
        initializeSchema();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "forgeoj_worker");
        registry.add("spring.datasource.password", () -> WORKER_PASSWORD);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> RABBIT_USER);
        registry.add("spring.rabbitmq.password", () -> RABBIT_PASSWORD);
        registry.add("spring.rabbitmq.virtual-host", () -> "/forgeoj");
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private DataSource workerDataSource;

    @BeforeEach
    void resetState() {
        JdbcTemplate migrator = migrator();
        migrator.update("DELETE FROM judge_task_attempt");
        migrator.update("DELETE FROM outbox_event");
        migrator.update("DELETE FROM judge_task");
        migrator.update("DELETE FROM submission");
        rabbitAdmin.purgeQueue(RabbitTopology.QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitTopology.RETRY_QUEUE, false);
    }

    @Test
    void consumesSnapshotsExecutesAndAcksOnlyAfterOleIsDurable() throws Exception {
        TaskIds ids = insertQueuedTask(OUTPUT_LIMIT_SOURCE, "m0-v1", 1024);

        publish(ids);
        await(() -> "FINISHED".equals(state(ids).get("task_status")), Duration.ofSeconds(20));
        Object finishedAt = state(ids).get("task_finished_at");
        publish(ids);
        awaitQueueDrained(Duration.ofSeconds(8));

        java.util.Map<String, Object> state = state(ids);
        assertThat(state)
                .containsEntry("task_status", "FINISHED")
                .containsEntry("processing_status", "FINISHED")
                .containsEntry("verdict", "OLE");
        assertThat(((Number) state.get("task_version")).longValue()).isEqualTo(2L);
        assertThat(((Number) state.get("submission_version")).longValue()).isEqualTo(2L);
        assertThat(state.get("task_finished_at")).isEqualTo(finishedAt);
    }

    @Test
    void deadLettersInvalidTrustedSandboxConfigurationBeforeAck() throws Exception {
        TaskIds ids = insertQueuedTask(SIMPLE_SOURCE, "unsupported-policy", 65536);

        publish(ids);
        await(() -> "DEAD_LETTER".equals(state(ids).get("task_status")), Duration.ofSeconds(8));
        awaitQueueDrained(Duration.ofSeconds(8));

        java.util.Map<String, Object> state = state(ids);
        assertThat(state)
                .containsEntry("task_status", "DEAD_LETTER")
                .containsEntry("processing_status", "SYSTEM_ERROR")
                .containsEntry("diagnostic_message", "Judging infrastructure failed")
                .containsEntry("attempt_status", "DEAD_LETTERED");
        assertThat(outboxEventType(ids)).isEqualTo("JUDGE_TASK_DEAD_LETTERED");
        assertThat(state.get("verdict")).isNull();
        assertThat(state.toString()).doesNotContain("unsupported-policy");
    }

    private void publish(TaskIds ids) {
        String body =
                """
                {"taskId":"%s","submissionId":"%s","taskType":"JUDGE_SUBMISSION","contractVersion":1}
                """
                        .formatted(ids.taskId(), ids.submissionId())
                        .strip();
        rabbitTemplate.convertAndSend(
                RabbitTopology.EXCHANGE,
                RabbitTopology.ROUTING_KEY,
                body,
                message -> {
                    message.getMessageProperties().setContentType("application/json");
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                });
    }

    private TaskIds insertQueuedTask(
            String sourceCode, String sandboxPolicyVersion, long outputLimitBytes) {
        String submissionId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        migrator().update(
                """
                INSERT INTO submission (
                    id, user_id, problem_id, judge_version_id, client_request_id,
                    language, source_code, source_sha256, time_limit_ms,
                    memory_limit_mb, output_limit_bytes, comparison_rule_version,
                    sandbox_policy_version, java_image_digest, test_dataset_sha256,
                    processing_status
                ) VALUES (?, 1, 1, 1, ?, 'JAVA_21', ?, ?, 2000, 256, ?,
                    'trim-trailing-whitespace-v1', ?,
                    'eclipse-temurin:21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438',
                    '37881a92ca996970e09475fdb29435b9bc13ae1501fa118e5fd9afd47e561adf',
                    'QUEUED')
                """,
                submissionId,
                UUID.randomUUID().toString(),
                sourceCode,
                sha256(sourceCode.getBytes(StandardCharsets.UTF_8)),
                outputLimitBytes,
                sandboxPolicyVersion);
        migrator().update(
                """
                INSERT INTO judge_task (
                    id, submission_id, task_type, contract_version, task_status
                ) VALUES (?, ?, 'JUDGE_SUBMISSION', 1, 'QUEUED')
                """,
                taskId,
                submissionId);
        return new TaskIds(taskId, submissionId);
    }

    private java.util.Map<String, Object> state(TaskIds ids) {
        return new JdbcTemplate(workerDataSource)
                .queryForMap(
                        """
                        SELECT jt.task_status,
                               jt.status_version AS task_version,
                               jt.finished_at AS task_finished_at,
                               s.processing_status,
                               s.verdict,
                               s.status_version AS submission_version,
                               s.diagnostic_message,
                               (SELECT a.attempt_status
                                FROM judge_task_attempt a
                                WHERE a.judge_task_id = jt.id
                                ORDER BY a.attempt_no DESC LIMIT 1) AS attempt_status
                        FROM judge_task jt
                        JOIN submission s ON s.id = jt.submission_id
                        WHERE jt.id = ? AND s.id = ?
                        """,
                        ids.taskId(),
                        ids.submissionId());
    }

    private String outboxEventType(TaskIds ids) {
        return migrator()
                .queryForObject(
                        """
                        SELECT event_type
                        FROM outbox_event
                        WHERE aggregate_id = ?
                        ORDER BY created_at DESC
                        LIMIT 1
                        """,
                        String.class,
                        ids.taskId());
    }

    private static synchronized void initializeSchema() throws Exception {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection =
                DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), "forgeoj_migrator", MIGRATOR_PASSWORD)) {
            executeScript(connection, "db/migration/V1__create_m0_core_schema.sql");
            executeScript(connection, "db/migration/V2__allow_ole_verdict.sql");
            executeScript(connection, "db/migration/V3__add_m1_attempt_lease_and_retry.sql");
            executeScript(connection, "db/devdata/R__seed_m0_development_data.sql");
        }
        schemaInitialized = true;
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

    private void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private void awaitQueueDrained(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String queueState;
        do {
            queueState = queueState();
            if (queueState.contains("\"forgeoj.judge.submission.v1\",\"0\",\"0\"")) {
                return;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertThat(queueState).contains("\"forgeoj.judge.submission.v1\",\"0\",\"0\"");
    }

    private String queueState() throws Exception {
        String queueState =
                RABBIT.execInContainer(
                                "rabbitmqctl",
                                "list_queues",
                                "--vhost",
                                "/forgeoj",
                                "name",
                                "messages_ready",
                                "messages_unacknowledged",
                                "--formatter=csv")
                        .getStdout();
        return queueState;
    }

    private record TaskIds(String taskId, String submissionId) {}
}
