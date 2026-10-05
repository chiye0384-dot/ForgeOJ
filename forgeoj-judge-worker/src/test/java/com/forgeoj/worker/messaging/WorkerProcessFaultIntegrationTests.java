package com.forgeoj.worker.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class WorkerProcessFaultIntegrationTests {

    private static final String MYSQL_IMAGE = "container-registry.oracle.com/mysql/community-server:8.4.12"
            + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be";
    private static final String RABBIT_IMAGE = "rabbitmq:4.3.6-management"
            + "@sha256:cdf40d8cb363d145e377ed88d59696a42386ffe54b30125f10eb128b862eea95";
    private static final String JAVA_IMAGE = "eclipse-temurin:21.0.12_8-jdk-jammy"
            + "@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438";
    private static final String SOURCE = "import java.util.Scanner; public class Main { public static void main(String[] args) {"
            + " Scanner s = new Scanner(System.in); System.out.println(s.nextLong() + s.nextLong()); } }"
            + " // FAULT_SOURCE_SENTINEL";
    private static final String WORKER_PASSWORD = "m0-worker-test-secret";
    private static final String RABBIT_PASSWORD = "fault-rabbit-test-secret";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container static final MySQLContainer MYSQL = new com.forgeoj.worker.testinfra.DirectMySQLContainer(DockerImageName.parse(MYSQL_IMAGE)
            .asCompatibleSubstituteFor("mysql")).withDatabaseName("forgeoj").withUsername("bootstrap")
            .withPassword("bootstrap-test-secret").withCopyFileToContainer(MountableFile.forHostPath(
                    repositoryFile("forgeoj-api/src/test/resources/mysql/init-test-users.sql")),
                    "/docker-entrypoint-initdb.d/01-init-test-users.sql");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse(RABBIT_IMAGE)
            .asCompatibleSubstituteFor("rabbitmq")).withAdminUser("forgeoj").withAdminPassword(RABBIT_PASSWORD)
            .withEnv("RABBITMQ_DEFAULT_VHOST", "/forgeoj");

    private static Connection connection;
    private static Channel channel;
    private final List<ChildWorker> children = new ArrayList<>();
    private final List<TaskIds> tasks = new ArrayList<>();

    @BeforeAll static void initialize() throws Exception {
        try (var database = DriverManager.getConnection(MYSQL.getJdbcUrl(), "forgeoj_migrator", "m0-migrator-test-secret")) {
            for (String script : List.of("db/migration/V1__create_m0_core_schema.sql", "db/migration/V2__allow_ole_verdict.sql",
                    "db/migration/V3__add_m1_attempt_lease_and_retry.sql", "db/migration/V4__add_user_judge_quota_lock.sql",
                    "db/migration/V5__widen_submission_verdict.sql",
                    "db/migration/V6__ordinary_accounts.sql",
                    "db/migration/V7__public_problem_library.sql",
                    "db/migration/V8__personal_learning_records.sql",
                    "db/migration/V9__authored_problem_drafts.sql",
                    "db/migration/V10__official_solution_access.sql",
                    "db/migration/V11__content_validation_jobs.sql",
                    "db/migration/V12__immutable_content_reviews.sql",
                    "db/migration/V13__content_output_previews.sql",
                    "db/migration/V14__independent_self_test.sql",
                    "db/devdata/R__seed_m0_development_data.sql")) {
                ScriptUtils.executeSqlScript(database, new FileSystemResource(repositoryFile("forgeoj-api/src/main/resources/" + script)));
            }
        }
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername("forgeoj");
        factory.setPassword(RABBIT_PASSWORD);
        factory.setVirtualHost("/forgeoj");
        connection = factory.newConnection("fault-test-publisher");
        channel = connection.createChannel();
        channel.exchangeDeclare(RabbitTopology.EXCHANGE, "direct", true);
        String[][] bindings = {{RabbitTopology.QUEUE, RabbitTopology.ROUTING_KEY},
                {RabbitTopology.RETRY_QUEUE, RabbitTopology.RETRY_ROUTING_KEY},
                {RabbitTopology.SELF_TEST_QUEUE, RabbitTopology.SELF_TEST_ROUTING_KEY},
                {RabbitTopology.DEAD_LETTER_QUEUE, RabbitTopology.DEAD_LETTER_ROUTING_KEY}};
        for (String[] binding : bindings) {
            channel.queueDeclare(binding[0], true, false, false, null);
            channel.queueBind(binding[0], RabbitTopology.EXCHANGE, binding[1]);
        }
        channel.confirmSelect();
    }

    @AfterAll static void closeBroker() throws Exception {
        if (channel != null) channel.close();
        if (connection != null) connection.close();
    }

    @BeforeEach void reset() throws Exception {
        // Test results must start without residual sandboxes; no other owner's container is removed here.
        assertThat(docker("container", "ls", "-aq", "--filter", "label=com.forgeoj.managed=true")).isBlank();
        JdbcTemplate jdbc = jdbc();
        for (String table : List.of("judge_task_attempt", "outbox_event", "judge_task", "submission")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (String queue : List.of(RabbitTopology.QUEUE, RabbitTopology.RETRY_QUEUE, RabbitTopology.DEAD_LETTER_QUEUE)) {
            channel.queuePurge(queue);
        }
    }

    @AfterEach void teardown() throws Exception {
        for (ChildWorker child : children) child.kill();
        // Only task IDs created by this method may be removed, not a global Docker prune/sweep.
        for (TaskIds task : tasks) {
            String listed = docker("container", "ls", "-aq", "--no-trunc", "--filter", "label=com.forgeoj.managed=true",
                    "--filter", "label=com.forgeoj.task-id=" + task.taskId());
            for (String id : listed.lines().filter(line -> !line.isBlank()).toList()) {
                assertThat(id).matches("[0-9a-f]{64}");
                var labels = JSON.readTree(docker("container", "inspect", "--format", "{{json .Config.Labels}}", id));
                assertThat(labels.path("com.forgeoj.task-id").asText()).isEqualTo(task.taskId());
                assertThat(labels.path("com.forgeoj.managed").asText()).isEqualTo("true");
                docker("container", "rm", "--force", "--volumes", id);
            }
        }
    }

    @Test void killedClaimOwnerIsRecoveredByAnotherJvmAfterRealLeaseExpiry() throws Exception {
        TaskIds task = insertTask();
        ChildWorker first = start("BEFORE_RUN", true);
        publish(task, RabbitTopology.ROUTING_KEY);
        first.awaitText("FAULT_BARRIER=BEFORE_RUN");
        assertThat(state(task)).containsEntry("task_status", "RUNNING").containsEntry("attempt_count", 1L);
        await(() -> queueNumber(RabbitTopology.QUEUE, "messages_unacknowledged") == 1, 15);
        first.kill();
        await(() -> queueNumber(RabbitTopology.QUEUE, "messages_ready") == 1, 15);
        ChildWorker second = start("NORMAL", true);
        awaitFinished(task, second);
        assertThat(attemptStatuses(task)).containsExactly("LEASE_EXPIRED", "SUCCEEDED");
        assertThat(state(task)).containsEntry("attempt_count", 2L).containsEntry("verdict", "AC");
        assertThat(second.text()).contains("\"event\":\"task.lease_recovered\"");
        assertNoSecrets(first, second);
        System.out.println("FAULT_VERIFIED=CLAIM_CRASH_REAL_EXPIRY_RECOVERED");
    }

    @Test void terminalCommitBeforeAckIsRedeliveredWithoutASecondExecution() throws Exception {
        TaskIds task = insertTask();
        ChildWorker first = start("BEFORE_ACK", true);
        publish(task, RabbitTopology.ROUTING_KEY);
        first.awaitText("FAULT_BARRIER=BEFORE_ACK");
        Map<String, Object> committed = state(task);
        assertThat(committed).containsEntry("task_status", "FINISHED").containsEntry("verdict", "AC")
                .containsEntry("attempt_count", 1L);
        await(() -> queueNumber(RabbitTopology.QUEUE, "messages_unacknowledged") == 1, 15);
        first.kill();
        await(() -> queueNumber(RabbitTopology.QUEUE, "messages_ready") == 1, 15);
        ChildWorker second = start("NORMAL", true);
        second.awaitText("\"outcome\":\"DUPLICATE\"");
        awaitDrained();
        assertThat(state(task)).isEqualTo(committed);
        assertThat(attemptStatuses(task)).containsExactly("SUCCEEDED");
        assertThat(second.text()).doesNotContain("\"event\":\"attempt.claimed\"", "\"event\":\"attempt.finished\"");
        // Also reorder/repeat formal and retry deliveries once the terminal result is durable.
        for (int i = 0; i < 3; i++) {
            publish(task, RabbitTopology.RETRY_ROUTING_KEY);
            publish(task, RabbitTopology.ROUTING_KEY);
        }
        await(() -> second.text().split("\"outcome\":\"DUPLICATE\"", -1).length >= 8, 20);
        awaitDrained();
        assertThat(state(task)).isEqualTo(committed);
        assertThat(attemptStatuses(task)).containsExactly("SUCCEEDED");
        assertNoSecrets(first, second);
        System.out.println("FAULT_VERIFIED=COMMIT_WITHOUT_ACK_REDELIVERY_AND_REORDER_ABSORBED");
    }

    @Test void alreadyRunningRecoveryWorkerHandlesOrphanSandboxAfterOwnerCrash() throws Exception {
        TaskIds task = insertTask();
        // B has already completed startup before A creates its sandbox; startup-only cleanup cannot recover it.
        ChildWorker second = start("NORMAL", false);
        ChildWorker first = start("WITH_SANDBOX", true);
        publish(task, RabbitTopology.ROUTING_KEY);
        first.awaitText("FAULT_BARRIER=WITH_SANDBOX");
        assertThat(taskContainers(task)).isNotBlank();
        first.kill();
        assertThat(taskContainers(task)).isNotBlank();
        second.startListeners();
        awaitFinished(task, second);
        assertThat(attemptStatuses(task)).containsExactly("LEASE_EXPIRED", "SUCCEEDED");
        assertThat(state(task)).containsEntry("attempt_count", 2L).containsEntry("verdict", "AC");
        await(() -> { try { return taskContainers(task).isBlank(); } catch (Exception failure) { return false; } }, 10);
        assertNoSecrets(first, second);
        System.out.println("FAULT_VERIFIED=ALREADY_RUNNING_WORKER_RECOVERS_ORPHAN_SANDBOX");
    }

    @Test void startingAnotherWorkerPreservesLiveSandboxThenRecoversItAfterCrash() throws Exception {
        TaskIds task = insertTask();
        ChildWorker first = start("WITH_SANDBOX", true);
        publish(task, RabbitTopology.ROUTING_KEY);
        first.awaitText("FAULT_BARRIER=WITH_SANDBOX");
        String active = taskContainers(task);
        assertThat(active).isNotBlank();
        ChildWorker second = start("NORMAL", false);
        // Both startup and periodic cleanup must leave A's still-RUNNING attempt alone.
        assertThat(taskContainers(task)).isEqualTo(active);
        assertThat(state(task)).containsEntry("task_status", "RUNNING").containsEntry("attempt_count", 1L);
        first.kill();
        second.startListeners();
        awaitFinished(task, second);
        assertThat(attemptStatuses(task)).containsExactly("LEASE_EXPIRED", "SUCCEEDED");
        assertThat(state(task)).containsEntry("attempt_count", 2L).containsEntry("verdict", "AC");
        await(() -> { try { return taskContainers(task).isBlank(); } catch (Exception failure) { return false; } }, 10);
        assertNoSecrets(first, second);
        System.out.println("FAULT_VERIFIED=STARTUP_PRESERVES_LIVE_OWNER_AND_LATER_RECOVERS_ORPHAN");
    }

    private ChildWorker start(String mode, boolean consume) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = List.of(java, "-Xmx256m", "-cp", System.getProperty("java.class.path"),
                FaultWorkerProcess.class.getName(), "--spring.config.location=classpath:/application.properties",
                "--forgeoj.worker.consumer.enabled=true", "--forgeoj.worker.sandbox.enabled=true",
                "--forgeoj.worker.recovery.enabled=true", "--forgeoj.worker.recovery.fixed-delay-ms=200",
                "--forgeoj.worker.lease-duration-seconds=9", "--forgeoj.worker.heartbeat-interval-millis=1000",
                "--forgeoj.worker.retry.base-delay-seconds=1", "--forgeoj.worker.retry.max-delay-seconds=1",
                "--forgeoj.worker.sandbox.cleanup-delay-ms=500",
                "--spring.rabbitmq.listener.simple.auto-startup=" + consume);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        var env = builder.environment();
        env.keySet().removeIf(key -> key.startsWith("SPRING_") || key.startsWith("FORGEOJ_"));
        env.put("SPRING_DATASOURCE_URL", MYSQL.getJdbcUrl());
        env.put("SPRING_DATASOURCE_USERNAME", "forgeoj_worker");
        env.put("SPRING_DATASOURCE_PASSWORD", WORKER_PASSWORD);
        env.put("SPRING_RABBITMQ_HOST", RABBIT.getHost());
        env.put("SPRING_RABBITMQ_PORT", RABBIT.getAmqpPort().toString());
        env.put("SPRING_RABBITMQ_USERNAME", "forgeoj");
        env.put("SPRING_RABBITMQ_PASSWORD", RABBIT_PASSWORD);
        env.put("SPRING_RABBITMQ_VIRTUAL_HOST", "/forgeoj");
        env.put("FORGEOJ_WORKER_INSTANCE_ID", "fault-" + UUID.randomUUID());
        env.put("FORGEOJ_TEST_MODE", mode);
        ChildWorker child = new ChildWorker(builder.start());
        children.add(child);
        child.awaitText("FAULT_PROCESS_READY");
        return child;
    }

    private TaskIds insertTask() throws Exception {
        return insertTask(SOURCE);
    }

    private TaskIds insertTask(String source) throws Exception {
        TaskIds task = new TaskIds(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        tasks.add(task);
        jdbc().update("""
                INSERT INTO submission (id,user_id,problem_id,judge_version_id,client_request_id,
                    language,source_code,source_sha256,time_limit_ms,memory_limit_mb,output_limit_bytes,
                    comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256,processing_status)
                VALUES (?,1,1,1,?,'JAVA_21',?,?,2000,256,65536,'trim-trailing-whitespace-v1','m0-v1',?,
                    '37881a92ca996970e09475fdb29435b9bc13ae1501fa118e5fd9afd47e561adf','QUEUED')
                """, task.submissionId(), UUID.randomUUID().toString(), source,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))), JAVA_IMAGE);
        jdbc().update("INSERT INTO judge_task(id,submission_id,task_type,contract_version,task_status) VALUES (?,?,'JUDGE_SUBMISSION',1,'QUEUED')",
                task.taskId(), task.submissionId());
        return task;
    }

    @Test void untrustedProgramCannotReadCredentialsFromItsRealWorkerProcess() throws Exception {
        TaskIds task = insertTask("""
                import java.nio.file.*;
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        for (String key : System.getenv().keySet()) {
                            if (key.startsWith("SPRING_") || key.startsWith("FORGEOJ_"))
                                throw new AssertionError("Worker configuration exposed");
                        }
                        try {
                            Files.readAllBytes(Path.of("/proc/1/environ"));
                            throw new AssertionError("Different UID process environment readable");
                        } catch (java.io.IOException denied) { }
                        for (String name : new String[]{"/var/run/docker.sock", "/app/application.properties", "/app/.env"}) {
                            if (Files.exists(Path.of(name))) throw new AssertionError("Worker mount exposed");
                        }
                        Scanner input = new Scanner(System.in);
                        System.out.println(input.nextLong() + input.nextLong());
                    }
                }
                """);
        ChildWorker worker = start("NORMAL", true);
        publish(task, RabbitTopology.ROUTING_KEY);
        awaitFinished(task, worker);
        assertThat(state(task)).containsEntry("attempt_count", 1L).containsEntry("verdict", "AC");
        assertThat(attemptStatuses(task)).containsExactly("SUCCEEDED");
        assertNoSecrets(worker);
        System.out.println("SECURITY_VERIFIED=REAL_WORKER_CREDENTIALS_NOT_VISIBLE_TO_USER_CODE");
    }

    private void publish(TaskIds task, String routing) throws Exception {
        byte[] body = JSON.writeValueAsBytes(Map.of("taskId", task.taskId(), "submissionId", task.submissionId(),
                "taskType", "JUDGE_SUBMISSION", "contractVersion", 1));
        channel.basicPublish(RabbitTopology.EXCHANGE, routing, true,
                new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build(), body);
        channel.waitForConfirmsOrDie(5000);
    }

    private Map<String, Object> state(TaskIds task) {
        return jdbc().queryForMap("""
                SELECT jt.task_status,jt.attempt_count,jt.status_version AS task_version,jt.finished_at,
                    s.processing_status,s.verdict,s.status_version AS submission_version,s.finished_at AS submission_finished_at
                FROM judge_task jt JOIN submission s ON s.id=jt.submission_id WHERE jt.id=?
                """, task.taskId());
    }

    private List<String> attemptStatuses(TaskIds task) {
        return jdbc().queryForList("SELECT attempt_status FROM judge_task_attempt WHERE judge_task_id=? ORDER BY attempt_no",
                String.class, task.taskId());
    }

    private void awaitFinished(TaskIds task, ChildWorker worker) throws Exception {
        await(() -> Set.of("FINISHED", "DEAD_LETTER", "SYSTEM_ERROR").contains(state(task).get("task_status")), 35);
        assertThat(state(task)).withFailMessage("Recovered task did not finish; worker output: %s", worker.text())
                .containsEntry("task_status", "FINISHED");
        awaitDrained();
    }

    private void awaitDrained() throws Exception {
        await(() -> queueNumber(RabbitTopology.QUEUE, "messages") == 0 && queueNumber(RabbitTopology.RETRY_QUEUE, "messages") == 0, 20);
    }

    private long queueNumber(String queue, String field) {
        try {
            String auth = Base64.getEncoder().encodeToString(("forgeoj:" + RABBIT_PASSWORD).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + RABBIT.getHost() + ":"
                    + RABBIT.getHttpPort() + "/api/queues/%2Fforgeoj/" + queue)).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Basic " + auth).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return -1;
            return JSON.readTree(response.body()).path(field).asLong(-1);
        } catch (Exception unavailable) {
            return -1;
        }
    }

    private void assertNoSecrets(ChildWorker... workers) {
        for (ChildWorker worker : workers) assertThat(worker.text()).doesNotContain("FAULT_SOURCE_SENTINEL", WORKER_PASSWORD, RABBIT_PASSWORD);
    }

    private String taskContainers(TaskIds task) throws Exception {
        return docker("container", "ls", "-aq", "--filter", "label=com.forgeoj.task-id=" + task.taskId());
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "forgeoj_migrator", "m0-migrator-test-secret"));
    }

    private static String docker(String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add("docker");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new java.util.concurrent.CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            try {
                output.complete(new String(process.getInputStream().readNBytes(65536), StandardCharsets.UTF_8));
            } catch (java.io.IOException failure) { output.completeExceptionally(failure); }
        });
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).withFailMessage("Docker test control timed out").isTrue();
            String captured = output.get(3, TimeUnit.SECONDS);
            assertThat(process.exitValue()).withFailMessage("Docker test control failed: %s", captured).isZero();
            return captured;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).withFailMessage("Fault test condition timed out").isTrue();
    }

    private static Path repositoryFile(String relative) {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve(relative))) return cursor.resolve(relative);
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Test repository file missing");
    }

    private static final class ChildWorker {
        private final Process process;
        private final StringBuilder output = new StringBuilder();
        private final Thread reader;

        ChildWorker(Process process) {
            this.process = process;
            reader = Thread.ofVirtual().start(() -> {
                try (var stream = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = stream.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() < 4 * 1024 * 1024) output.append(line).append('\n');
                        }
                    }
                } catch (java.io.IOException ignored) { }
            });
        }

        String text() { synchronized (output) { return output.toString(); } }

        void awaitText(String expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!text().contains(expected) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
            assertThat(text()).withFailMessage("Child Worker missing marker %s; output: %s", expected, text()).contains(expected);
        }

        void startListeners() throws Exception {
            process.getOutputStream().write('S');
            process.getOutputStream().flush();
            awaitText("FAULT_LISTENERS_STARTED");
        }

        void kill() throws Exception {
            if (process.isAlive()) process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            reader.join(2000);
        }
    }

    private record TaskIds(String taskId, String submissionId) {}
}
