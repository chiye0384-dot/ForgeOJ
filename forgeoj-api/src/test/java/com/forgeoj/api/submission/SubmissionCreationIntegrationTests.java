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
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
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
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
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
                    // Disposable failure injection only; production does not need this setting.
                    .withCommand("--log-bin-trust-function-creators=1")
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

    @Autowired
    private SubmissionTransactionService transactionService;

    @BeforeEach
    void resetSubmissionState() {
        JdbcTemplate migrator = migratorJdbc();
        migrator.update("DELETE FROM outbox_event");
        migrator.update("DELETE FROM judge_task_attempt");
        migrator.update("DELETE FROM judge_task");
        migrator.update("DELETE FROM submission");
    }

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
    void limitsQueuedAndRetryingSubmissionsToThreePerUser() throws Exception {
        AuthenticatedSession session = login();
        UUID firstKey = UUID.randomUUID();

        submit(session, firstKey, VALID_SOURCE).andExpect(status().isAccepted());
        submit(session, UUID.randomUUID(), VALID_SOURCE).andExpect(status().isAccepted());
        submit(session, UUID.randomUUID(), VALID_SOURCE).andExpect(status().isAccepted());

        submit(session, UUID.randomUUID(), VALID_SOURCE)
                .andExpect(status().isTooManyRequests());
        submit(session, firstKey, VALID_SOURCE).andExpect(status().isAccepted());

        JdbcTemplate api = new JdbcTemplate(apiDataSource);
        assertThat(
                        api.queryForObject(
                                """
                                SELECT COUNT(*)
                                FROM submission
                                WHERE user_id = 1
                                  AND processing_status IN ('QUEUED', 'RETRYING')
                                """,
                                Integer.class))
                .isEqualTo(3);
    }

    @Test
    void concurrentDistinctSubmissionsCannotExceedQueuedQuota() throws Exception {
        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(callers)) {
            List<Future<Object>> futures =
                    java.util.stream.IntStream.range(0, callers)
                            .mapToObj(
                                    ignored ->
                                            executor.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        start.await();
                                                        try {
                                                            submissionService.create(
                                                                    1L,
                                                                    "sum-two-integers",
                                                                    UUID.randomUUID().toString(),
                                                                    "JAVA_21",
                                                                    VALID_SOURCE);
                                                            accepted.incrementAndGet();
                                                        } catch (org.springframework.web.server.ResponseStatusException quota) {
                                                            assertThat(quota.getStatusCode().value())
                                                                    .isEqualTo(429);
                                                            rejected.incrementAndGet();
                                                        }
                                                        return null;
                                                    }))
                            .toList();

            ready.await();
            start.countDown();
            for (Future<Object> future : futures) {
                future.get();
            }
        }

        assertThat(accepted).hasValue(3);
        assertThat(rejected).hasValue(callers - 3);
        assertThat(
                        new JdbcTemplate(apiDataSource)
                                .queryForObject(
                                        """
                                        SELECT COUNT(*)
                                        FROM submission
                                        WHERE user_id = 1
                                          AND processing_status IN ('QUEUED', 'RETRYING')
                                        """,
                                        Integer.class))
                .isEqualTo(3);
    }

    @Test
    void allowsThreeQueuedTasksAlongsideARunningTaskThatCanRetry() throws Exception {
        AuthenticatedSession session = login();
        String runningSubmission = createSubmission(session);
        createSubmission(session);
        migratorJdbc()
                .update(
                        """
                        UPDATE judge_task jt
                        JOIN submission s ON s.id = jt.submission_id
                        SET jt.task_status = 'RUNNING', jt.status_version = 1,
                            jt.attempt_count = 1,
                            s.processing_status = 'RUNNING', s.status_version = 1
                        WHERE s.id = ?
                        """,
                        runningSubmission);

        submit(session, UUID.randomUUID(), VALID_SOURCE).andExpect(status().isAccepted());
        submit(session, UUID.randomUUID(), VALID_SOURCE).andExpect(status().isAccepted());
        submit(session, UUID.randomUUID(), VALID_SOURCE)
                .andExpect(status().isTooManyRequests());

        JdbcTemplate api = new JdbcTemplate(apiDataSource);
        assertThat(
                        api.queryForObject(
                                """
                                SELECT COUNT(*) FROM submission
                                WHERE user_id = 1 AND processing_status = 'RUNNING'
                                """,
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        api.queryForObject(
                                """
                                SELECT COUNT(*) FROM submission
                                WHERE user_id = 1
                                  AND processing_status IN ('QUEUED', 'RETRYING')
                                """,
                                Integer.class))
                .isEqualTo(3);
    }

    @Test
    void concurrentReplayOfLastAvailableSlotSucceedsAfterQuotaLock() throws Exception {
        AuthenticatedSession owner = login();
        createSubmission(owner);
        createSubmission(owner);
        UUID key = UUID.randomUUID();
        String sourceHash = sha256(VALID_SOURCE);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<SubmissionResult>> results = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> executor.submit(() -> {
                        start.await();
                        return transactionService.createNew(1L, "sum-two-integers", key,
                                "JAVA_21", VALID_SOURCE, sourceHash);
                    })).toList();
            start.countDown();
            HashSet<String> ids = new HashSet<>();
            for (Future<SubmissionResult> result : results) {
                ids.add(result.get(10, java.util.concurrent.TimeUnit.SECONDS).submissionId());
            }
            assertThat(ids).hasSize(1);
            assertSingleRecordSet(key, ids.iterator().next());
        }
    }

    @Test
    void cancellationIsAtomicIdempotentAndReleasesQueuedQuota() throws Exception {
        AuthenticatedSession owner = login();
        String submissionId = createSubmission(owner);
        createSubmission(owner);
        createSubmission(owner);
        MvcResult response = cancelSubmission(owner, submissionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.statusVersion").value(1)).andReturn();
        Map<String, Object> publicResponse = JsonPath.read(response.getResponse().getContentAsString(), "$");
        assertThat(publicResponse.keySet())
                .containsExactlyInAnyOrder("submissionId", "processingStatus", "statusVersion");
        Map<String, Object> afterFirstCancellation = cancellationState(submissionId);
        cancelSubmission(owner, submissionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusVersion").value(1));
        assertThat(cancellationState(submissionId)).isEqualTo(afterFirstCancellation);
        Map<String, Object> cancelled = cancellationState(submissionId);
        assertThat(cancelled)
                .containsEntry("task_status", "CANCELLED")
                .containsEntry("processing_status", "CANCELLED")
                .containsEntry("attempt_count", 0L)
                .containsEntry("verdict", null);
        assertThat(((Number) cancelled.get("task_version")).longValue()).isEqualTo(1);
        assertThat(((Number) cancelled.get("submission_version")).longValue()).isEqualTo(1);
        assertThat(cancelled.get("task_finished_at")).isNotNull();
        assertThat(cancelled.get("submission_finished_at")).isNotNull();
        assertThat(migratorJdbc().queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class))
                .isEqualTo(3);
        assertThat(migratorJdbc().queryForObject("SELECT COUNT(*) FROM judge_task_attempt", Integer.class))
                .isZero();
        submit(owner, UUID.randomUUID(), VALID_SOURCE).andExpect(status().isAccepted());
    }

    @Test
    void concurrentCancellationOnlyIncrementsVersionsOnce() throws Exception {
        AuthenticatedSession owner = login();
        String submissionId = createSubmission(owner);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> executor.submit(() -> {
                        start.await();
                        return cancelSubmission(owner, submissionId).andReturn().getResponse().getStatus();
                    })).toList();
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            }
        }
        Map<String, Object> cancelled = cancellationState(submissionId);
        assertThat(((Number) cancelled.get("task_version")).longValue()).isEqualTo(1);
        assertThat(((Number) cancelled.get("submission_version")).longValue()).isEqualTo(1);
    }

    @Test
    void cancellationWaitsForConcurrentRunningTransitionAndThenRejects() throws Exception {
        AuthenticatedSession owner = login();
        String submissionId = createSubmission(owner);
        String taskId = migratorJdbc().queryForObject(
                "SELECT id FROM judge_task WHERE submission_id = ?", String.class, submissionId);
        try (var connection = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "forgeoj_worker", "m0-worker-test-secret");
                var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("""
                    SELECT jt.id FROM judge_task jt JOIN submission s ON s.id = jt.submission_id
                    WHERE jt.id = ? FOR UPDATE
                    """)) {
                lock.setString(1, taskId);
                try (var row = lock.executeQuery()) {
                    assertThat(row.next()).isTrue();
                }
            }
            CountDownLatch ready = new CountDownLatch(1);
            Future<Integer> cancellation = executor.submit(() -> {
                ready.countDown();
                return cancelSubmission(owner, submissionId).andReturn().getResponse().getStatus();
            });
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try (var transition = connection.prepareStatement("""
                    UPDATE judge_task jt JOIN submission s ON s.id = jt.submission_id
                    SET jt.task_status = 'RUNNING', jt.status_version = 1,
                        s.processing_status = 'RUNNING', s.status_version = 1
                    WHERE jt.id = ?
                    """)) {
                transition.setString(1, taskId);
                assertThat(transition.executeUpdate()).isEqualTo(2);
            }
            connection.commit();
            assertThat(cancellation.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertThat(cancellationState(submissionId))
                .containsEntry("task_status", "RUNNING")
                .containsEntry("processing_status", "RUNNING");
    }

    @Test
    void queuedQuotaIsIndependentForDifferentUsersAndCountsRetrying() throws Exception {
        AuthenticatedSession owner = login();
        String retrying = createSubmission(owner);
        migratorJdbc().update("""
                UPDATE judge_task jt JOIN submission s ON s.id = jt.submission_id
                SET jt.task_status = 'RETRYING', s.processing_status = 'RETRYING' WHERE s.id = ?
                """, retrying);
        createSubmission(owner);
        createSubmission(owner);
        submit(owner, UUID.randomUUID(), VALID_SOURCE).andExpect(status().isTooManyRequests());
        createSecondUser();
        AuthenticatedSession other = login("another-learner");
        createSubmission(other);
        createSubmission(other);
        createSubmission(other);
        submit(other, UUID.randomUUID(), VALID_SOURCE).andExpect(status().isTooManyRequests());
    }

    @Test
    void cancellationRejectsNonQueuedStatesWithoutChangingEitherTable() throws Exception {
        AuthenticatedSession owner = login();
        for (String state : List.of("RUNNING", "RETRYING", "FINISHED", "SYSTEM_ERROR")) {
            String submissionId = createSubmission(owner);
            migratorJdbc().update("""
                    UPDATE judge_task jt JOIN submission s ON s.id = jt.submission_id
                    SET jt.task_status = ?, s.processing_status = ?,
                        s.verdict = CASE WHEN ? = 'FINISHED' THEN 'AC' ELSE NULL END
                    WHERE s.id = ?
                    """, state, state, state, submissionId);
            Map<String, Object> before = cancellationState(submissionId);
            cancelSubmission(owner, submissionId).andExpect(status().isConflict());
            assertThat(cancellationState(submissionId)).isEqualTo(before);
            // Release the disposable fixture so each state starts with a fresh quota.
            resetSubmissionState();
        }
    }

    @Test
    void cancellationHidesOtherOwnerMissingAndMalformedResourcesAndRequiresCsrf() throws Exception {
        AuthenticatedSession owner = login();
        String submissionId = createSubmission(owner);
        createSecondUser();
        AuthenticatedSession other = login("another-learner");
        Map<String, Object> before = cancellationState(submissionId);
        MvcResult otherOwner = cancelSubmission(other, submissionId)
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missing = cancelSubmission(owner, UUID.randomUUID().toString())
                .andExpect(status().isNotFound()).andReturn();
        MvcResult malformed = cancelSubmission(owner, "not-a-uuid")
                .andExpect(status().isNotFound()).andReturn();
        assertThat(otherOwner.getResponse().getErrorMessage())
                .isEqualTo(missing.getResponse().getErrorMessage())
                .isEqualTo(malformed.getResponse().getErrorMessage());
        assertThat(otherOwner.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString())
                .isEqualTo(malformed.getResponse().getContentAsString());
        mockMvc.perform(post("/api/v1/submissions/{submissionId}/cancel", submissionId)
                        .session(owner.session())).andExpect(status().isForbidden());
        AnonymousSession anonymous = openAnonymousSession();
        mockMvc.perform(post("/api/v1/submissions/{submissionId}/cancel", submissionId)
                        .session(anonymous.session())
                        .header(anonymous.csrfHeader(), anonymous.csrfToken()))
                .andExpect(status().isUnauthorized());
        assertThat(cancellationState(submissionId)).isEqualTo(before);
    }

    @Test
    void cancellationRollsBackTaskWhenSubmissionUpdateFails() throws Exception {
        AuthenticatedSession owner = login();
        String submissionId = createSubmission(owner);
        Map<String, Object> before = cancellationState(submissionId);
        JdbcTemplate migrator = migratorJdbc();
        migrator.execute("""
                CREATE TRIGGER fail_cancellation BEFORE UPDATE ON submission FOR EACH ROW
                BEGIN
                    IF NEW.processing_status = 'CANCELLED' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Injected cancellation failure';
                    END IF;
                END
                """);
        try {
            assertThatThrownBy(() -> cancelSubmission(owner, submissionId))
                    .isInstanceOf(Exception.class);
        } finally {
            migrator.execute("DROP TRIGGER fail_cancellation");
        }
        assertThat(cancellationState(submissionId)).isEqualTo(before);
    }

    private org.springframework.test.web.servlet.ResultActions cancelSubmission(
            AuthenticatedSession session, String submissionId) throws Exception {
        return mockMvc.perform(post("/api/v1/submissions/{submissionId}/cancel", submissionId)
                .session(session.session()).header(session.csrfHeader(), session.csrfToken()));
    }

    private Map<String, Object> cancellationState(String submissionId) {
        return migratorJdbc().queryForMap("""
                SELECT jt.task_status, s.processing_status,
                       jt.status_version AS task_version, s.status_version AS submission_version,
                       jt.attempt_count, s.verdict, jt.finished_at AS task_finished_at,
                       s.finished_at AS submission_finished_at
                FROM judge_task jt JOIN submission s ON s.id = jt.submission_id
                WHERE s.id = ?
                """, submissionId);
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
        migratorJdbc().update("INSERT IGNORE INTO user_judge_quota_lock (user_id) VALUES (2)");
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
