package com.forgeoj.worker.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeoj.worker.messaging.JudgeTaskMessage;
import com.forgeoj.worker.messaging.JudgeTaskRunner;
import com.forgeoj.worker.messaging.RabbitTopology;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import tools.jackson.databind.ObjectMapper;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(JudgeTaskClaimIntegrationTests.RunnerConfiguration.class)
@SpringBootTest(
        properties = {
            "forgeoj.worker.consumer.enabled=true",
            "forgeoj.worker.instance-id=claim-test-worker",
            "forgeoj.worker.lease-duration-seconds=30",
            "spring.rabbitmq.listener.simple.acknowledge-mode=manual",
            "spring.rabbitmq.listener.simple.prefetch=1"
        })
class JudgeTaskClaimIntegrationTests {

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
    private JudgeTaskClaimService claimService;

    @Autowired
    private JudgeTaskLeaseService leaseService;

    @Autowired
    private JudgeTaskRecoveryMapper recoveryMapper;

    @Autowired
    private RecordingRunner runner;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource workerDataSource;

    @BeforeEach
    void resetState() {
        JdbcTemplate migrator = migratorJdbc();
        migrator.update("DELETE FROM judge_task_attempt");
        migrator.update("DELETE FROM outbox_event");
        migrator.update("DELETE FROM judge_task");
        migrator.update("DELETE FROM submission");
        rabbitAdmin.purgeQueue(RabbitTopology.QUEUE, false);
        runner.reset();
    }

    @Test
    void duplicateRabbitDeliveryRunsClaimedTaskOnceAndLeavesNoUnackedMessage()
            throws Exception {
        TaskIds task = insertQueuedTask();
        String body = messageJson(task.taskId(), task.submissionId());

        publish(body);
        assertThat(runner.awaitInvocations(1, Duration.ofSeconds(5))).isTrue();
        publish(body);

        await(
                () ->
                        runner.invocationCount() == 1
                                && rabbitAdmin.getQueueInfo(RabbitTopology.QUEUE).getMessageCount()
                                        == 0,
                Duration.ofSeconds(5));

        JdbcTemplate worker = new JdbcTemplate(workerDataSource);
        java.util.Map<String, Object> state =
                taskState(worker, task.taskId(), task.submissionId());
        assertThat(state)
                .containsEntry("task_status", "RUNNING")
                .containsEntry("processing_status", "RUNNING")
                .containsEntry("attempt_count", 1L)
                .containsEntry("attempt_rows", 1L);
        assertThat(state.get("lease_token")).isNotNull();
        assertThat(((Number) state.get("task_version")).longValue()).isEqualTo(1L);
        assertThat(((Number) state.get("submission_version")).longValue()).isEqualTo(1L);
        assertThat(runner.messages())
                .containsExactly(
                        new JudgeTaskMessage(
                                task.taskId(),
                                task.submissionId(),
                                "JUDGE_SUBMISSION",
                                1));

        String queue =
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
        assertThat(queue).contains("\"forgeoj.judge.submission.v1\",\"0\",\"0\"");
    }

    @Test
    void concurrentClaimsGiveExecutionOwnershipToExactlyOneCaller() throws Exception {
        TaskIds task = insertQueuedTask();
        JudgeTaskMessage message = validMessage(task);
        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(callers)) {
            List<Future<TaskClaimResult>> futures = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return claimService.claim(message);
                                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<TaskClaimOutcome> outcomes = new ArrayList<>();
            for (Future<TaskClaimResult> future : futures) {
                outcomes.add(future.get(10, TimeUnit.SECONDS).outcome());
            }
            assertThat(outcomes).containsOnly(TaskClaimOutcome.CLAIMED, TaskClaimOutcome.DUPLICATE);
            assertThat(outcomes.stream().filter(TaskClaimOutcome.CLAIMED::equals).count())
                    .isEqualTo(1);
        }
    }

    @Test
    void mismatchedTaskAndSubmissionFailsClosedWithoutChangingEitherRecord() {
        TaskIds first = insertQueuedTask();
        TaskIds second = insertQueuedTask();

        assertThat(
                        claimService
                                .claim(
                                new JudgeTaskMessage(
                                        first.taskId(),
                                        second.submissionId(),
                                        "JUDGE_SUBMISSION",
                                        1))
                                .outcome())
                .isEqualTo(TaskClaimOutcome.REJECTED);

        JdbcTemplate worker = new JdbcTemplate(workerDataSource);
        java.util.Map<String, Object> state =
                taskState(worker, first.taskId(), first.submissionId());
        assertThat(state)
                .containsEntry("task_status", "QUEUED")
                .containsEntry("processing_status", "QUEUED")
                .containsEntry("attempt_count", 0L)
                .containsEntry("attempt_rows", 0L);
        assertThat(((Number) state.get("task_version")).longValue()).isZero();
        assertThat(((Number) state.get("submission_version")).longValue()).isZero();
    }

    @Test
    void rollsBackTaskClaimWhenSubmissionUpdateIsDenied() {
        TaskIds task = insertQueuedTask();
        JdbcTemplate migrator = migratorJdbc();
        migrator.execute("REVOKE UPDATE ON forgeoj.submission FROM 'forgeoj_worker'@'%'");

        try {
            assertThatThrownBy(() -> claimService.claim(validMessage(task)))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            migrator.execute(
                    "GRANT SELECT, UPDATE ON forgeoj.submission TO 'forgeoj_worker'@'%'");
        }

        JdbcTemplate worker = new JdbcTemplate(workerDataSource);
        java.util.Map<String, Object> state =
                taskState(worker, task.taskId(), task.submissionId());
        assertThat(state)
                .containsEntry("task_status", "QUEUED")
                .containsEntry("processing_status", "QUEUED")
                .containsEntry("attempt_count", 0L)
                .containsEntry("attempt_rows", 0L);
        assertThat(((Number) state.get("task_version")).longValue()).isZero();
        assertThat(((Number) state.get("submission_version")).longValue()).isZero();
    }

    @Test
    void expiredLeaseCreatesANewAttemptAndExpiresTheOldOwner() {
        TaskIds task = insertQueuedTask();

        TaskClaimResult first = claimService.claim(validMessage(task));
        assertThat(first.outcome()).isEqualTo(TaskClaimOutcome.CLAIMED);

        JdbcTemplate migrator = migratorJdbc();
        migrator.update(
                "UPDATE judge_task SET lease_expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?",
                task.taskId());
        migrator.update(
                "UPDATE judge_task_attempt SET lease_expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?",
                first.claimedTask().attemptId());

        TaskClaimResult recovered = claimService.claim(validMessage(task));

        assertThat(recovered.outcome()).isEqualTo(TaskClaimOutcome.CLAIMED);
        assertThat(recovered.claimedTask().attemptNo()).isEqualTo(2);
        assertThat(recovered.claimedTask().leaseToken())
                .isNotEqualTo(first.claimedTask().leaseToken());
        assertThat(
                        migrator.queryForObject(
                                "SELECT attempt_status FROM judge_task_attempt WHERE id = ?",
                                String.class,
                                first.claimedTask().attemptId()))
                .isEqualTo("LEASE_EXPIRED");
        assertThat(
                        migrator.queryForObject(
                                "SELECT COUNT(*) FROM judge_task_attempt WHERE judge_task_id = ?",
                                Long.class,
                                task.taskId()))
                .isEqualTo(2L);
    }

    @Test
    void recoveryScanRepublishesExpiredLeaseForAnotherAttempt() throws Exception {
        TaskIds task = insertQueuedTask();
        TaskClaimResult first = claimService.claim(validMessage(task));
        assertThat(first.outcome()).isEqualTo(TaskClaimOutcome.CLAIMED);
        expireLease(migratorJdbc(), task.taskId(), first.claimedTask().attemptId());

        JudgeTaskRecoveryScanner scanner =
                new JudgeTaskRecoveryScanner(
                        recoveryMapper, rabbitTemplate, objectMapper, 20);
        assertThat(scanner.recoverDueTasks()).isEqualTo(1);

        assertThat(runner.awaitInvocations(1, Duration.ofSeconds(5))).isTrue();
        await(
                () ->
                        ((Number)
                                                taskState(
                                                                new JdbcTemplate(workerDataSource),
                                                                task.taskId(),
                                                                task.submissionId())
                                                        .get("attempt_count"))
                                        .longValue()
                                == 2L,
                Duration.ofSeconds(5));

        JdbcTemplate migrator = migratorJdbc();
        assertThat(
                        migrator.queryForObject(
                                "SELECT attempt_status FROM judge_task_attempt WHERE id = ?",
                                String.class,
                                first.claimedTask().attemptId()))
                .isEqualTo("LEASE_EXPIRED");
    }

    @Test
    void recoveryScanRepublishesRetryOnlyAfterItBecomesDue() throws Exception {
        TaskIds task = insertQueuedTask();
        JdbcTemplate migrator = migratorJdbc();
        migrator.update(
                "UPDATE judge_task SET task_status = 'RETRYING', next_attempt_at = CURRENT_TIMESTAMP(6) + INTERVAL 1 HOUR WHERE id = ?",
                task.taskId());
        migrator.update(
                "UPDATE submission SET processing_status = 'RETRYING' WHERE id = ?",
                task.submissionId());
        JudgeTaskRecoveryScanner scanner =
                new JudgeTaskRecoveryScanner(
                        recoveryMapper, rabbitTemplate, objectMapper, 20);

        assertThat(scanner.recoverDueTasks()).isZero();
        migrator.update(
                "UPDATE judge_task SET next_attempt_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?",
                task.taskId());
        assertThat(scanner.recoverDueTasks()).isEqualTo(1);

        assertThat(runner.awaitInvocations(1, Duration.ofSeconds(5))).isTrue();
        await(
                () ->
                        "RUNNING".equals(
                                taskState(
                                                new JdbcTemplate(workerDataSource),
                                                task.taskId(),
                                                task.submissionId())
                                        .get("task_status")),
                Duration.ofSeconds(5));
    }

    @Test
    void heartbeatRenewsTaskAndAttemptLeaseTogether() {
        TaskIds task = insertQueuedTask();
        TaskClaimResult claim = claimService.claim(validMessage(task));
        assertThat(claim.outcome()).isEqualTo(TaskClaimOutcome.CLAIMED);

        JdbcTemplate migrator = migratorJdbc();
        migrator.update(
                "UPDATE judge_task SET lease_expires_at = CURRENT_TIMESTAMP(6) + INTERVAL 1 SECOND WHERE id = ?",
                task.taskId());
        migrator.update(
                "UPDATE judge_task_attempt SET lease_expires_at = CURRENT_TIMESTAMP(6) + INTERVAL 1 SECOND WHERE id = ?",
                claim.claimedTask().attemptId());

        LocalDateTime previousExpiry =
                migrator.queryForObject(
                        "SELECT lease_expires_at FROM judge_task WHERE id = ?",
                        LocalDateTime.class,
                        task.taskId());

        leaseService.renew(claim.claimedTask());

        java.util.Map<String, Object> leaseState =
                migrator.queryForMap(
                        """
                        SELECT jt.lease_expires_at AS task_expiry,
                               a.lease_expires_at AS attempt_expiry,
                               a.heartbeat_at
                        FROM judge_task jt
                        JOIN judge_task_attempt a ON a.judge_task_id = jt.id
                        WHERE jt.id = ? AND a.id = ?
                        """,
                        task.taskId(),
                        claim.claimedTask().attemptId());
        assertThat(leaseState.get("task_expiry")).isEqualTo(leaseState.get("attempt_expiry"));
        assertThat((LocalDateTime) leaseState.get("task_expiry")).isAfter(previousExpiry);
        assertThat(leaseState.get("heartbeat_at")).isNotNull();
    }

    @Test
    void heartbeatRollsBackWhenAttemptOwnershipNoLongerMatches() {
        TaskIds task = insertQueuedTask();
        TaskClaimResult claim = claimService.claim(validMessage(task));
        assertThat(claim.outcome()).isEqualTo(TaskClaimOutcome.CLAIMED);

        JdbcTemplate migrator = migratorJdbc();
        LocalDateTime previousExpiry =
                migrator.queryForObject(
                        "SELECT lease_expires_at FROM judge_task WHERE id = ?",
                        LocalDateTime.class,
                        task.taskId());
        ClaimedJudgeTask staleAttempt =
                new ClaimedJudgeTask(
                        claim.claimedTask().message(),
                        UUID.randomUUID().toString(),
                        claim.claimedTask().attemptNo(),
                        claim.claimedTask().leaseToken(),
                        claim.claimedTask().workerId());

        assertThatThrownBy(() -> leaseService.renew(staleAttempt))
                .isInstanceOf(LeaseOwnershipLostException.class);

        assertThat(
                        migrator.queryForObject(
                                "SELECT lease_expires_at FROM judge_task WHERE id = ?",
                                LocalDateTime.class,
                                task.taskId()))
                .isEqualTo(previousExpiry);
    }

    @Test
    void expiredLeaseAtAttemptLimitAtomicallyMovesTaskToDeadLetter() {
        TaskIds task = insertQueuedTask();
        TaskClaimResult current = claimService.claim(validMessage(task));
        JdbcTemplate migrator = migratorJdbc();

        for (int attemptNo = 1; attemptNo < 3; attemptNo++) {
            expireLease(migrator, task.taskId(), current.claimedTask().attemptId());
            current = claimService.claim(validMessage(task));
            assertThat(current.outcome()).isEqualTo(TaskClaimOutcome.CLAIMED);
            assertThat(current.claimedTask().attemptNo()).isEqualTo(attemptNo + 1);
        }

        expireLease(migrator, task.taskId(), current.claimedTask().attemptId());
        TaskClaimResult exhausted = claimService.claim(validMessage(task));

        assertThat(exhausted.outcome()).isEqualTo(TaskClaimOutcome.EXHAUSTED);
        assertThat(taskState(new JdbcTemplate(workerDataSource), task.taskId(), task.submissionId()))
                .containsEntry("task_status", "DEAD_LETTER")
                .containsEntry("processing_status", "SYSTEM_ERROR")
                .containsEntry("attempt_count", 3L);
        assertThat(
                        migrator.queryForObject(
                                "SELECT attempt_status FROM judge_task_attempt WHERE id = ?",
                                String.class,
                                current.claimedTask().attemptId()))
                .isEqualTo("DEAD_LETTERED");
        assertThat(
                        migrator.queryForObject(
                                "SELECT event_type FROM outbox_event WHERE aggregate_id = ?",
                                String.class,
                                task.taskId()))
                .isEqualTo("JUDGE_TASK_DEAD_LETTERED");
    }

    private void expireLease(JdbcTemplate migrator, String taskId, String attemptId) {
        migrator.update(
                "UPDATE judge_task SET lease_expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?",
                taskId);
        migrator.update(
                "UPDATE judge_task_attempt SET lease_expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?",
                attemptId);
    }

    private void publish(String body) {
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

    private TaskIds insertQueuedTask() {
        String submissionId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        JdbcTemplate migrator = migratorJdbc();
        migrator.update(
                """
                INSERT INTO submission (
                    id, user_id, problem_id, judge_version_id, client_request_id,
                    language, source_code, source_sha256, time_limit_ms,
                    memory_limit_mb, output_limit_bytes, comparison_rule_version,
                    sandbox_policy_version, java_image_digest, test_dataset_sha256,
                    processing_status
                ) VALUES (?, 1, 1, 1, ?, 'JAVA_21', 'public class Main {}',
                    REPEAT('a', 64), 2000, 256, 1048576,
                    'trim-trailing-whitespace-v1', 'm0-v1',
                    'eclipse-temurin:test', REPEAT('b', 64), 'QUEUED')
                """,
                submissionId,
                UUID.randomUUID().toString());
        migrator.update(
                """
                INSERT INTO judge_task (
                    id, submission_id, task_type, contract_version, task_status
                ) VALUES (?, ?, 'JUDGE_SUBMISSION', 1, 'QUEUED')
                """,
                taskId,
                submissionId);
        return new TaskIds(taskId, submissionId);
    }

    private java.util.Map<String, Object> taskState(
            JdbcTemplate worker, String taskId, String submissionId) {
        return worker.queryForMap(
                """
                SELECT jt.task_status,
                       jt.status_version AS task_version,
                       jt.attempt_count,
                       jt.lease_token,
                       s.processing_status,
                       s.status_version AS submission_version,
                       (SELECT COUNT(*) FROM judge_task_attempt a
                        WHERE a.judge_task_id = jt.id) AS attempt_rows
                FROM judge_task jt
                JOIN submission s ON s.id = jt.submission_id
                WHERE jt.id = ? AND s.id = ?
                """,
                taskId,
                submissionId);
    }

    private JudgeTaskMessage validMessage(TaskIds task) {
        return new JudgeTaskMessage(
                task.taskId(), task.submissionId(), "JUDGE_SUBMISSION", 1);
    }

    private String messageJson(String taskId, String submissionId) {
        return """
                {"taskId":"%s","submissionId":"%s","taskType":"JUDGE_SUBMISSION","contractVersion":1}
                """
                .formatted(taskId, submissionId)
                .strip();
    }

    private static synchronized void initializeSchema() throws Exception {
        if (schemaInitialized) {
            return;
        }
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
                                    "forgeoj-api/src/main/resources/db/devdata/"
                                            + "R__seed_m0_development_data.sql")));
        }
        schemaInitialized = true;
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

    private JdbcTemplate migratorJdbc() {
        return new JdbcTemplate(
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), "forgeoj_migrator", MIGRATOR_PASSWORD));
    }

    private void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    record TaskIds(String taskId, String submissionId) {}

    @TestConfiguration
    static class RunnerConfiguration {

        @Bean
        RecordingRunner recordingRunner() {
            return new RecordingRunner();
        }
    }

    static final class RecordingRunner implements JudgeTaskRunner {

        private final AtomicInteger invocations = new AtomicInteger();
        private final CopyOnWriteArrayList<JudgeTaskMessage> messages =
                new CopyOnWriteArrayList<>();
        private volatile CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void run(ClaimedJudgeTask claimedTask) {
            messages.add(claimedTask.message());
            invocations.incrementAndGet();
            latch.countDown();
        }

        void reset() {
            messages.clear();
            invocations.set(0);
            latch = new CountDownLatch(1);
        }

        boolean awaitInvocations(int expected, Duration timeout) throws InterruptedException {
            if (invocations.get() >= expected) {
                return true;
            }
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    && invocations.get() >= expected;
        }

        int invocationCount() {
            return invocations.get();
        }

        List<JudgeTaskMessage> messages() {
            return List.copyOf(messages);
        }
    }
}
