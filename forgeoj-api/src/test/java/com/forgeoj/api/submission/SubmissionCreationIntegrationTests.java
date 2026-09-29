package com.forgeoj.api.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/devdata",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "spring.rabbitmq.listener.direct.auto-startup=false"
        })
class SubmissionCreationIntegrationTests {

    private static final String MYSQL_IMAGE =
            "container-registry.oracle.com/mysql/community-server:8.4.12"
                    + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be";
    private static final String API_PASSWORD = "m0-api-test-secret";
    private static final String MIGRATOR_PASSWORD = "m0-migrator-test-secret";
    private static final String VALID_SOURCE =
            """
            import java.util.Scanner;

            public class Main {
                public static void main(String[] args) {
                    Scanner scanner = new Scanner(System.in);
                    System.out.println(scanner.nextLong() + scanner.nextLong());
                }
            }
            // SOURCE_SENTINEL_MUST_NOT_ENTER_OUTBOX
            """;

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(
                            DockerImageName.parse(MYSQL_IMAGE)
                                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("forgeoj")
                    .withUsername("bootstrap")
                    .withPassword("bootstrap-test-secret")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("mysql/init-test-users.sql"),
                            "/docker-entrypoint-initdb.d/01-init-test-users.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "forgeoj_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "forgeoj_migrator");
        registry.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource apiDataSource;

    @Autowired
    private SubmissionService submissionService;

    @Test
    void createsExactlyOneRecordSetAndReplaysSequentially() throws Exception {
        AuthenticatedSession session = login();
        UUID firstKey = UUID.randomUUID();

        MvcResult first =
                submit(session, firstKey, VALID_SOURCE).andExpect(status().isAccepted()).andReturn();
        String firstSubmissionId =
                JsonPath.read(first.getResponse().getContentAsString(), "$.submissionId");

        MvcResult replay =
                submit(session, firstKey, VALID_SOURCE).andExpect(status().isAccepted()).andReturn();
        String replaySubmissionId =
                JsonPath.read(replay.getResponse().getContentAsString(), "$.submissionId");

        assertThat(replaySubmissionId).isEqualTo(firstSubmissionId);
        assertSingleRecordSet(firstKey, firstSubmissionId);

        UUID secondKey = UUID.randomUUID();
        MvcResult second =
                submit(session, secondKey, VALID_SOURCE).andExpect(status().isAccepted()).andReturn();
        String secondSubmissionId =
                JsonPath.read(second.getResponse().getContentAsString(), "$.submissionId");

        assertThat(secondSubmissionId).isNotEqualTo(firstSubmissionId);
        assertSingleRecordSet(secondKey, secondSubmissionId);
    }

    @Test
    void concurrentReplayCreatesOneRecordSet() throws Exception {
        UUID requestId = UUID.randomUUID();
        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(callers)) {
            List<Future<SubmissionResult>> futures =
                    java.util.stream.IntStream.range(0, callers)
                            .mapToObj(
                                    ignored ->
                                            executor.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        start.await();
                                                        return submissionService.create(
                                                                1L,
                                                                "sum-two-integers",
                                                                requestId.toString(),
                                                                "JAVA_21",
                                                                VALID_SOURCE);
                                                    }))
                            .toList();

            ready.await();
            start.countDown();

            HashSet<String> submissionIds = new HashSet<>();
            for (Future<SubmissionResult> future : futures) {
                submissionIds.add(future.get().submissionId());
            }

            assertThat(submissionIds).hasSize(1);
            assertSingleRecordSet(requestId, submissionIds.iterator().next());
        }
    }

    @Test
    void rollsBackSubmissionAndTaskWhenOutboxInsertFails() {
        JdbcTemplate api = new JdbcTemplate(apiDataSource);
        JdbcTemplate migrator = migratorJdbc();
        UUID requestId = UUID.randomUUID();
        int submissionsBefore = count(api, "submission");
        int tasksBefore = count(api, "judge_task");
        int outboxBefore = count(api, "outbox_event");

        migrator.execute(
                "REVOKE INSERT ON forgeoj.outbox_event FROM 'forgeoj_api'@'%'");

        try {
            assertThatThrownBy(
                            () ->
                                    submissionService.create(
                                            1L,
                                            "sum-two-integers",
                                            requestId.toString(),
                                            "JAVA_21",
                                            VALID_SOURCE))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            migrator.execute(
                    "GRANT INSERT ON forgeoj.outbox_event TO 'forgeoj_api'@'%'");
        }

        assertThat(count(api, "submission")).isEqualTo(submissionsBefore);
        assertThat(count(api, "judge_task")).isEqualTo(tasksBefore);
        assertThat(count(api, "outbox_event")).isEqualTo(outboxBefore);
        assertThat(
                        api.queryForObject(
                                "SELECT COUNT(*) FROM submission WHERE client_request_id = ?",
                                Integer.class,
                                requestId.toString()))
                .isZero();
    }

    @Test
    void rejectsAnonymousAndInvalidSubmissionInput() throws Exception {
        AnonymousSession anonymous = openAnonymousSession();
        mockMvc.perform(
                        post("/api/v1/problems/sum-two-integers/submissions")
                                .session(anonymous.session())
                                .header(anonymous.csrfHeader(), anonymous.csrfToken())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestJson("JAVA_21", VALID_SOURCE)))
                .andExpect(status().isUnauthorized());

        AuthenticatedSession session = login();
        submit(session, UUID.randomUUID(), "package copied.example; public class Main {}")
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        post("/api/v1/problems/sum-two-integers/submissions")
                                .session(session.session())
                                .header(session.csrfHeader(), session.csrfToken())
                                .header("Idempotency-Key", "not-a-uuid")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestJson("JAVA_21", VALID_SOURCE)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        post("/api/v1/problems/sum-two-integers/submissions")
                                .session(session.session())
                                .header(session.csrfHeader(), session.csrfToken())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestJson("PYTHON", VALID_SOURCE)))
                .andExpect(status().isBadRequest());

        submit(session, UUID.randomUUID(), "// public class Main only appears in a comment")
                .andExpect(status().isBadRequest());

        submit(
                        session,
                        UUID.randomUUID(),
                        "public class Main { /* " + "x".repeat(65536) + " */ }")
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsOnlyWhitelistedSubmissionStatusFieldsToOwner() throws Exception {
        AuthenticatedSession owner = login();
        String submissionId = createSubmission(owner);

        MvcResult queued =
                getSubmission(owner, submissionId).andExpect(status().isOk()).andReturn();
        Map<String, Object> queuedBody =
                JsonPath.parse(queued.getResponse().getContentAsString()).read("$");
        assertThat(queuedBody.keySet())
                .containsExactlyInAnyOrder(
                        "submissionId",
                        "processingStatus",
                        "statusVersion",
                        "verdict",
                        "diagnosticMessage");
        assertThat(queuedBody)
                .containsEntry("submissionId", submissionId)
                .containsEntry("processingStatus", "QUEUED")
                .containsEntry("statusVersion", 0)
                .containsEntry("verdict", null)
                .containsEntry("diagnosticMessage", null);

        migratorJdbc()
                .update(
                        """
                        UPDATE submission
                        SET processing_status = 'FINISHED', verdict = 'OLE', status_version = 2,
                            diagnostic_message = 'HIDDEN_DIAGNOSTIC_SENTINEL',
                            finished_at = CURRENT_TIMESTAMP(6)
                        WHERE id = ?
                        """,
                        submissionId);

        MvcResult finished =
                getSubmission(owner, submissionId).andExpect(status().isOk()).andReturn();
        String finishedJson = finished.getResponse().getContentAsString();
        Map<String, Object> finishedBody = JsonPath.parse(finishedJson).read("$");
        assertThat(finishedBody)
                .containsEntry("processingStatus", "FINISHED")
                .containsEntry("statusVersion", 2)
                .containsEntry("verdict", "OLE")
                .containsEntry("diagnosticMessage", null);
        assertThat(finishedJson)
                .doesNotContain("HIDDEN_DIAGNOSTIC_SENTINEL")
                .doesNotContain("SOURCE_SENTINEL_MUST_NOT_ENTER_OUTBOX")
                .doesNotContain(
                        "37881a92ca996970e09475fdb29435b9bc13ae1501fa118e5fd9afd47e561adf");
    }

    @Test
    void returnsSameNotFoundForAnotherOwnerMissingAndMalformedIds() throws Exception {
        AuthenticatedSession owner = login();
        String submissionId = createSubmission(owner);
        createSecondUser();
        AuthenticatedSession anotherUser = login("another-learner");

        getSubmission(anotherUser, submissionId).andExpect(status().isNotFound());
        getSubmission(owner, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        getSubmission(owner, "not-a-uuid").andExpect(status().isNotFound());
    }

    @Test
    void rejectsAnonymousSubmissionStatusReads() throws Exception {
        AuthenticatedSession owner = login();
        String submissionId = createSubmission(owner);

        mockMvc.perform(get("/api/v1/submissions/{submissionId}", submissionId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exposesCompileDiagnosticsButNormalizesPlatformFailureDetails() throws Exception {
        AuthenticatedSession owner = login();
        String compileErrorId = createSubmission(owner);
        String systemErrorId = createSubmission(owner);
        JdbcTemplate migrator = migratorJdbc();
        migrator.update(
                """
                UPDATE submission
                SET processing_status = 'FINISHED', verdict = 'CE', status_version = 2,
                    diagnostic_message = 'Main.java:3: missing semicolon',
                    finished_at = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """,
                compileErrorId);
        migrator.update(
                """
                UPDATE submission
                SET processing_status = 'SYSTEM_ERROR', verdict = NULL, status_version = 2,
                    diagnostic_message = 'jdbc:mysql://secret-host/forgeoj HIDDEN_SENTINEL',
                    finished_at = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """,
                systemErrorId);

        getSubmission(owner, compileErrorId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosticMessage").value("Main.java:3: missing semicolon"));
        MvcResult systemError =
                getSubmission(owner, systemErrorId).andExpect(status().isOk()).andReturn();
        String systemErrorJson = systemError.getResponse().getContentAsString();
        assertThat(JsonPath.read(systemErrorJson, "$.diagnosticMessage").toString())
                .isEqualTo("Judging infrastructure failed");
        assertThat(systemErrorJson)
                .doesNotContain("secret-host")
                .doesNotContain("HIDDEN_SENTINEL");
    }

    private org.springframework.test.web.servlet.ResultActions submit(
            AuthenticatedSession session, UUID requestId, String sourceCode) throws Exception {
        return mockMvc.perform(
                post("/api/v1/problems/sum-two-integers/submissions")
                        .session(session.session())
                        .header(session.csrfHeader(), session.csrfToken())
                        .header("Idempotency-Key", requestId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("JAVA_21", sourceCode)));
    }

    private org.springframework.test.web.servlet.ResultActions getSubmission(
            AuthenticatedSession session, String submissionId) throws Exception {
        return mockMvc.perform(
                get("/api/v1/submissions/{submissionId}", submissionId)
                        .session(session.session()));
    }

    private String createSubmission(AuthenticatedSession session) throws Exception {
        MvcResult result =
                submit(session, UUID.randomUUID(), VALID_SOURCE)
                        .andExpect(status().isAccepted())
                        .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.submissionId");
    }

    private void assertSingleRecordSet(UUID requestId, String submissionId) throws Exception {
        JdbcTemplate api = new JdbcTemplate(apiDataSource);
        assertThat(
                        api.queryForObject(
                                "SELECT COUNT(*) FROM submission WHERE user_id = 1 AND client_request_id = ?",
                                Integer.class,
                                requestId.toString()))
                .isEqualTo(1);

        var submission =
                api.queryForMap(
                        """
                        SELECT processing_status, status_version, source_sha256,
                               time_limit_ms, memory_limit_mb, output_limit_bytes,
                               comparison_rule_version, sandbox_policy_version,
                               java_image_digest, test_dataset_sha256
                        FROM submission
                        WHERE id = ?
                        """,
                        submissionId);
        assertThat(submission.get("processing_status")).isEqualTo("QUEUED");
        assertThat(((Number) submission.get("status_version")).longValue()).isZero();
        assertThat(submission.get("source_sha256")).isEqualTo(sha256(VALID_SOURCE));
        assertThat(((Number) submission.get("time_limit_ms")).intValue()).isEqualTo(2000);
        assertThat(((Number) submission.get("memory_limit_mb")).intValue()).isEqualTo(256);
        assertThat(((Number) submission.get("output_limit_bytes")).longValue()).isEqualTo(1048576L);
        assertThat(submission.get("comparison_rule_version"))
                .isEqualTo("trim-trailing-whitespace-v1");
        assertThat(submission.get("sandbox_policy_version")).isEqualTo("m0-v1");
        assertThat(submission.get("java_image_digest"))
                .isEqualTo(
                        "eclipse-temurin:21.0.12_8-jdk-jammy@sha256:"
                                + "c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438");
        assertThat(submission.get("test_dataset_sha256"))
                .isEqualTo("37881a92ca996970e09475fdb29435b9bc13ae1501fa118e5fd9afd47e561adf");

        String taskId =
                api.queryForObject(
                        "SELECT id FROM judge_task WHERE submission_id = ?", String.class, submissionId);
        assertThat(taskId).isNotNull();
        assertThat(
                        api.queryForObject(
                                "SELECT COUNT(*) FROM judge_task WHERE submission_id = ?",
                                Integer.class,
                                submissionId))
                .isEqualTo(1);

        var event =
                api.queryForMap(
                        """
                        SELECT aggregate_type, event_type, contract_version,
                               JSON_LENGTH(payload) AS payload_size,
                               JSON_UNQUOTE(JSON_EXTRACT(payload, '$.taskId')) AS payload_task_id,
                               JSON_UNQUOTE(JSON_EXTRACT(payload, '$.submissionId')) AS payload_submission_id,
                               JSON_UNQUOTE(JSON_EXTRACT(payload, '$.taskType')) AS payload_task_type,
                               JSON_EXTRACT(payload, '$.contractVersion') AS payload_contract_version,
                               CAST(payload AS CHAR) AS payload_text
                        FROM outbox_event
                        WHERE aggregate_id = ?
                        """,
                        taskId);
        assertThat(event.get("aggregate_type")).isEqualTo("JUDGE_TASK");
        assertThat(event.get("event_type")).isEqualTo("JUDGE_TASK_QUEUED");
        assertThat(((Number) event.get("contract_version")).intValue()).isEqualTo(1);
        assertThat(((Number) event.get("payload_size")).intValue()).isEqualTo(4);
        assertThat(event.get("payload_task_id")).isEqualTo(taskId);
        assertThat(event.get("payload_submission_id")).isEqualTo(submissionId);
        assertThat(event.get("payload_task_type")).isEqualTo("JUDGE_SUBMISSION");
        assertThat(event.get("payload_contract_version").toString()).isEqualTo("1");
        assertThat(event.get("payload_text").toString())
                .doesNotContain("SOURCE_SENTINEL_MUST_NOT_ENTER_OUTBOX")
                .doesNotContain("37881a92ca996970e09475fdb29435b9bc13ae1501fa118e5fd9afd47e561adf");
    }

    private AuthenticatedSession login() throws Exception {
        return login("learner");
    }

    private AuthenticatedSession login(String username) throws Exception {
        AnonymousSession anonymous = openAnonymousSession();
        MvcResult login =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .session(anonymous.session())
                                        .header(anonymous.csrfHeader(), anonymous.csrfToken())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"username":"%s","password":"forgeoj-dev-only"}
                                                """
                                                        .formatted(username)
                                                        .strip()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.authenticated").value(true))
                        .andReturn();
        return new AuthenticatedSession(
                (MockHttpSession) login.getRequest().getSession(false),
                anonymous.csrfHeader(),
                anonymous.csrfToken());
    }

    private void createSecondUser() {
        migratorJdbc()
                .update(
                        """
                        INSERT INTO user_account (id, username, password_hash, status)
                        VALUES (2, 'another-learner',
                            '$2a$10$slWnrjf2WJd.j/4Fnc1m..7QjwDgtyAlJ4OrLRzEjhAN0g7zkuyCi',
                            'ACTIVE') AS new
                        ON DUPLICATE KEY UPDATE
                            password_hash = new.password_hash,
                            status = new.status
                        """);
    }

    private AnonymousSession openAnonymousSession() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/api/v1/auth/session"))
                        .andExpect(status().isOk())
                        .andReturn();
        String body = result.getResponse().getContentAsString();
        return new AnonymousSession(
                (MockHttpSession) result.getRequest().getSession(false),
                JsonPath.read(body, "$.csrf.headerName"),
                JsonPath.read(body, "$.csrf.token"));
    }

    private JdbcTemplate migratorJdbc() {
        return new JdbcTemplate(
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), "forgeoj_migrator", MIGRATOR_PASSWORD));
    }

    private int count(JdbcTemplate jdbcTemplate, String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String requestJson(String language, String sourceCode) {
        return """
                {"language":"%s","sourceCode":"%s"}
                """
                .formatted(language, jsonEscape(sourceCode));
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private String sha256(String value) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record AnonymousSession(
            MockHttpSession session, String csrfHeader, String csrfToken) {}

    private record AuthenticatedSession(
            MockHttpSession session, String csrfHeader, String csrfToken) {}
}
