package com.forgeoj.api.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.locations=classpath:db/migration,classpath:db/devdata",
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.rabbitmq.listener.direct.auto-startup=false",
    "forgeoj.notifications.fixed-delay-ms=100",
    "forgeoj.notifications.max-connections=2",
    "forgeoj.notifications.max-connections-per-user=2"
})
class SubmissionNotificationIntegrationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse(
            "container-registry.oracle.com/mysql/community-server:8.4.12"
                    + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be")
            .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret")
            .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),
                    "/docker-entrypoint-initdb.d/01-init-test-users.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "forgeoj_api");
        registry.add("spring.datasource.password", () -> "m0-api-test-secret");
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "forgeoj_migrator");
        registry.add("spring.flyway.password", () -> "m0-migrator-test-secret");
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void resetFixtures() {
        JdbcTemplate migrator = migrator();
        migrator.update("DELETE FROM outbox_event");
        migrator.update("DELETE FROM judge_task_attempt");
        migrator.update("DELETE FROM judge_task");
        migrator.update("DELETE FROM submission");
        migrator.update("UPDATE user_account SET status = 'ACTIVE' WHERE id = 1");
        migrator.update("""
                INSERT INTO user_account (id, username, password_hash, status)
                SELECT 2, 'notification-other', password_hash, 'ACTIVE' FROM user_account WHERE id = 1
                ON DUPLICATE KEY UPDATE status = 'ACTIVE'
                """);
        migrator.update("INSERT IGNORE INTO user_judge_quota_lock (user_id) VALUES (2)");
    }

    @Test
    void ownerReceivesOnlyThreeFieldsAndMonotonicCommittedSnapshots() throws Exception {
        Login owner = login("learner");
        String id = createSubmission(owner);
        Frames frames = new Frames();
        WebSocket socket = open(owner.client(), id, origin(), frames);
        try {
            assertNotice(frames.next(), id, "QUEUED", 0);
            assertThat(frames.messages.poll(250, TimeUnit.MILLISECONDS)).isNull();
            JdbcTemplate worker = worker();
            worker.update("UPDATE submission SET processing_status = 'RUNNING', status_version = 1 WHERE id = ?", id);
            assertNotice(frames.next(), id, "RUNNING", 1);
            worker.update("""
                    UPDATE submission SET processing_status = 'FINISHED', verdict = 'CE', status_version = 2,
                        diagnostic_message = 'Main.java: compiler detail WS_PRIVATE_SENTINEL'
                    WHERE id = ?
                    """, id);
            String terminal = frames.next();
            assertNotice(terminal, id, "FINISHED", 2);
            assertThat(terminal).doesNotContain("CE", "compiler", "WS_PRIVATE_SENTINEL");
            assertThat(frames.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
            JsonNode actual = request(owner.client(), "GET", "/api/v1/submissions/" + id, null, null);
            assertThat(actual.path("verdict").asText()).isEqualTo("CE");
        } finally {
            socket.abort();
        }
    }

    @Test
    void anonymousAndNonOwnersCannotSubscribeAndMissingIdsAreIndistinguishable() throws Exception {
        Login owner = login("learner");
        String id = createSubmission(owner);
        Login other = login("notification-other");
        assertRejected(newClient(), id, origin(), 401);
        assertRejected(other.client(), id, origin(), 404);
        assertRejected(owner.client(), UUID.randomUUID().toString(), origin(), 404);
        assertRejected(owner.client(), "not-a-uuid", origin(), 404);
    }

    @Test
    void missingNullCrossOriginAndMalformedOriginsFailClosed() throws Exception {
        Login owner = login("learner");
        String id = createSubmission(owner);
        for (String foreign : new String[] {null, "null", "https://evil.example", origin() + "/path"}) {
            assertRejected(owner.client(), id, foreign, 403);
        }
    }

    @Test
    void logoutClosesExistingSubscriptionWithoutFurtherDisclosure() throws Exception {
        Login owner = login("learner");
        String id = createSubmission(owner);
        Frames frames = new Frames();
        WebSocket socket = open(owner.client(), id, origin(), frames);
        try {
            assertNotice(frames.next(), id, "QUEUED", 0);
            request(owner.client(), "POST", "/api/v1/auth/logout", "", owner);
            assertThat(frames.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(frames.messages).isEmpty();
        } finally { socket.abort(); }
    }

    @Test
    void disabledAccountAndClientCommandsCloseSubscription() throws Exception {
        Login owner = login("learner");
        String id = createSubmission(owner);
        Frames first = new Frames();
        WebSocket socket = open(owner.client(), id, origin(), first);
        try {
            first.next();
            migrator().update("UPDATE user_account SET status = 'DISABLED' WHERE id = 1");
            assertThat(first.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        } finally { socket.abort(); }
        migrator().update("UPDATE user_account SET status = 'ACTIVE' WHERE id = 1");
        Frames second = new Frames();
        WebSocket next = open(owner.client(), id, origin(), second);
        try {
            second.next();
            next.sendText("{\"command\":\"read-other-submission\"}", true).join();
            assertThat(second.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        } finally { next.abort(); }
    }

    @Test
    void excessConnectionsAreClosedAndCancelledStateIsNotified() throws Exception {
        Login owner = login("learner");
        String id = createSubmission(owner);
        Frames first = new Frames();
        Frames second = new Frames();
        Frames excess = new Frames();
        WebSocket one = open(owner.client(), id, origin(), first);
        WebSocket two = open(owner.client(), id, origin(), second);
        WebSocket three = open(owner.client(), id, origin(), excess);
        try {
            first.next(); second.next();
            assertThat(excess.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1013);
            request(owner.client(), "POST", "/api/v1/submissions/" + id + "/cancel", "", owner);
            assertNotice(first.next(), id, "CANCELLED", 1);
            assertNotice(second.next(), id, "CANCELLED", 1);
            assertThat(first.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
            assertThat(second.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
        } finally { one.abort(); two.abort(); three.abort(); }
    }

    private void assertNotice(String payload, String id, String status, long version) {
        JsonNode node = json.readTree(payload);
        Set<String> keys = new java.util.HashSet<>();
        node.propertyNames().forEach(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("submissionId", "processingStatus", "statusVersion");
        assertThat(node.path("submissionId").asText()).isEqualTo(id);
        assertThat(node.path("processingStatus").asText()).isEqualTo(status);
        assertThat(node.path("statusVersion").asLong()).isEqualTo(version);
    }

    private Login login(String username) throws Exception {
        HttpClient client = newClient();
        JsonNode anonymous = request(client, "GET", "/api/v1/auth/session", null, null);
        Login csrf = new Login(client, anonymous.path("csrf").path("headerName").asText(),
                anonymous.path("csrf").path("token").asText());
        JsonNode loggedIn = request(client, "POST", "/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"forgeoj-dev-only\"}", csrf);
        assertThat(loggedIn.path("authenticated").asBoolean()).isTrue();
        return new Login(client, loggedIn.path("csrf").path("headerName").asText(),
                loggedIn.path("csrf").path("token").asText());
    }

    private String createSubmission(Login owner) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(origin() + "/api/v1/problems/sum-two-integers/submissions"))
                .header(owner.csrfHeader(), owner.csrfToken()).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"language\":\"JAVA_21\",\"sourceCode\":\"public class Main {}\"}"))
                .timeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = owner.client().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).path("submissionId").asText();
    }

    private JsonNode request(HttpClient client, String method, String path, String body, Login csrf) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin() + path))
                .timeout(Duration.ofSeconds(5));
        if (csrf != null) request.header(csrf.csrfHeader(), csrf.csrfToken());
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
        return response.body().isBlank() ? null : json.readTree(response.body());
    }

    private WebSocket open(HttpClient client, String id, String origin, Frames frames) throws Exception {
        var builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
        if (origin != null) builder.header("Origin", origin);
        return builder.buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/submissions/" + id + "/events"), frames)
                .get(5, TimeUnit.SECONDS);
    }

    private void assertRejected(HttpClient client, String id, String origin, int status) throws Exception {
        try {
            WebSocket unexpected = open(client, id, origin, new Frames());
            unexpected.abort();
            throw new AssertionError("Expected rejected handshake");
        } catch (java.util.concurrent.ExecutionException failure) {
            assertThat(failure.getCause()).isInstanceOf(WebSocketHandshakeException.class);
            assertThat(((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode()).isEqualTo(status);
        }
    }

    private HttpClient newClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    private String origin() { return "http://127.0.0.1:" + port; }
    private JdbcTemplate migrator() { return new JdbcTemplate(new DriverManagerDataSource(
            MYSQL.getJdbcUrl(), "forgeoj_migrator", "m0-migrator-test-secret")); }
    private JdbcTemplate worker() { return new JdbcTemplate(new DriverManagerDataSource(
            MYSQL.getJdbcUrl(), "forgeoj_worker", "m0-worker-test-secret")); }
    record Login(HttpClient client, String csrfHeader, String csrfToken) {}

    static class Frames implements WebSocket.Listener {
        final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder partial = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) { messages.offer(partial.toString()); partial.setLength(0); }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closed.complete(status); return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
        String next() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).isNotNull();
            return message;
        }
    }
}
