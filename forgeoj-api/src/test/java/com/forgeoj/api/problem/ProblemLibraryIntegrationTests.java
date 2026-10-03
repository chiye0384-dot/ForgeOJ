/*
 * Copyright 2026 池也
 * SPDX-License-Identifier: Apache-2.0
 */
package com.forgeoj.api.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Real Tomcat, security, MyBatis and least-privilege MySQL; only disposable fixture data. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.locations=classpath:db/migration,classpath:db/devdata",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class ProblemLibraryIntegrationTests {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12"
                    + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("forgeoj").withUsername("bootstrap")
            .withPassword("bootstrap-test-secret")
            .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),
                    "/docker-entrypoint-initdb.d/01-users.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "forgeoj_api");
        registry.add("spring.datasource.password", () -> "m0-api-test-secret");
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "forgeoj_migrator");
        registry.add("spring.flyway.password", () -> "m0-migrator-test-secret");
    }

    @LocalServerPort private int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void seedPublicMetadata() {
        JdbcTemplate db = migrator();
        db.update("DELETE FROM problem_tag");
        db.update("UPDATE problem SET current_judge_version_id = NULL WHERE id >= 100");
        db.update("DELETE FROM problem_judge_version WHERE problem_id >= 100");
        db.update("DELETE FROM problem WHERE id >= 100");
        db.update("UPDATE problem SET difficulty = NULL WHERE id = 1");
        problem(db, 101, "larger-two", "50X complete aXb xxy", "EASY", "ACTIVE", true);
        problem(db, 102, "literal-percent", "50% complete", "MEDIUM", "ACTIVE", true);
        problem(db, 103, "literal-underscore", "a_b", "HARD", "ACTIVE", true);
        problem(db, 104, "literal-equals", "x=y", null, "ACTIVE", true);
        problem(db, 105, "archived-fixture", "private archived fixture", "EASY", "ARCHIVED", true);
        problem(db, 106, "no-version-fixture", "unpublished fixture", "EASY", "ACTIVE", false);
        problem(db, 107, "tags", "标签同名题", "EASY", "ACTIVE", true);
        db.update("""
                INSERT INTO problem_tag (problem_id, tag) VALUES
                (101, '数学'), (101, '入门'), (102, '数学'), (102, '百分号'),
                (103, '字符'), (104, '字符'), (105, 'archived-only'),
                (106, 'unpublished-only'), (107, '字符串')
                """);
    }

    @Test
    void anonymousListHasExactWhitelistStablePaginationAndLegacyNullDifficulty() throws Exception {
        HttpResponse<String> response = get("/api/v1/problems?size=2");
        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> page = object(response.body());
        assertThat(page.keySet()).containsExactlyInAnyOrder("items", "page", "size", "total");
        assertThat(page).containsEntry("page", 1).containsEntry("size", 2).containsEntry("total", 6);
        List<Map<String, Object>> rows = JsonPath.read(response.body(), "$.items");
        assertThat(rows).extracting(row -> row.get("slug")).containsExactly("sum-two-integers", "larger-two");
        for (Map<String, Object> row : rows) {
            assertThat(row.keySet()).containsExactlyInAnyOrder("slug", "title", "difficulty", "tags", "judgeVersion");
        }
        assertThat(rows.getFirst()).containsEntry("difficulty", null).containsEntry("tags", List.of());
        assertThat(rows.get(1)).containsEntry("difficulty", "EASY");
        assertThat((List<String>) rows.get(1).get("tags")).containsExactlyInAnyOrder("入门", "数学");
        assertThat(response.body()).doesNotContain("statement", "resourceLimits", "sourceCode", "sha256",
                "archived", "unpublished", "hidden");
        assertThat(slugs(get("/api/v1/problems?page=2&size=2")))
                .containsExactly("literal-percent", "literal-underscore");
        assertThat(slugs(get("/api/v1/problems?page=3&size=2")))
                .containsExactly("literal-equals", "tags");
        HttpResponse<String> beyond = get("/api/v1/problems?page=2147483647&size=50");
        assertThat(beyond.statusCode()).isEqualTo(200);
        assertThat(slugs(beyond)).isEmpty();
        assertThat((Integer) JsonPath.read(beyond.body(), "$.total")).isEqualTo(6);
    }

    @Test
    void combinesDifficultyTagAndLiteralTitleFiltersWithoutDuplicates() throws Exception {
        assertThat(slugs(get("/api/v1/problems?tag=" + encode("数学"))))
                .containsExactly("larger-two", "literal-percent");
        assertThat(slugs(get("/api/v1/problems?difficulty=EASY&tag=" + encode("数学"))))
                .containsExactly("larger-two");
        assertThat(slugs(get("/api/v1/problems?keyword=" + encode("%"))))
                .containsExactly("literal-percent");
        assertThat(slugs(get("/api/v1/problems?keyword=" + encode("a_b"))))
                .containsExactly("literal-underscore");
        assertThat(slugs(get("/api/v1/problems?keyword=" + encode("="))))
                .containsExactly("literal-equals");
        assertThat(slugs(get("/api/v1/problems?keyword=" + encode("%' OR 1=1 --"))))
                .isEmpty();
        assertThat(slugs(get("/api/v1/problems?tag=" + encode("数学' OR 1=1 --"))))
                .isEmpty();
        assertThat(slugs(get("/api/v1/problems?keyword=" + encode("  50%  "))))
                .containsExactly("literal-percent");
        assertThat(slugs(get("/api/v1/problems?keyword=absent&difficulty=EASY"))).isEmpty();
    }

    @Test
    void tagsOnlyDescribeEligibleProblemsAndDoNotOccupyTheExistingTagsSlug() throws Exception {
        HttpResponse<String> response = get("/api/v1/problem-tags");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(object(response.body()).keySet()).containsExactly("tags");
        List<String> tags = JsonPath.read(response.body(), "$.tags");
        assertThat(tags).containsExactlyInAnyOrder("入门", "数学", "百分号", "字符", "字符串");
        assertThat(tags).doesNotHaveDuplicates();
        HttpResponse<String> existingSlug = get("/api/v1/problems/tags");
        assertThat(existingSlug.statusCode()).isEqualTo(200);
        assertThat(object(existingSlug.body()).keySet()).containsExactlyInAnyOrder("slug", "title", "statement",
                "inputDescription", "outputDescription", "publicSamples", "judgeVersion", "resourceLimits");
        for (String slug : List.of("archived-fixture", "no-version-fixture", "missing")) {
            HttpResponse<String> unavailable = get("/api/v1/problems/" + slug);
            assertThat(unavailable.statusCode()).isEqualTo(404);
            assertThat(unavailable.body()).isEmpty();
        }
    }

    @Test
    void realServletReturnsEmpty400ForInvalidQueriesAndOnlyGetIsPublic() throws Exception {
        for (String query : List.of("page=0", "page=-1", "page=2147483648", "page=abc", "size=0", "size=51",
                "size=abc", "difficulty=IMPOSSIBLE", "difficulty=easy", "keyword=" + "x".repeat(101),
                "tag=" + "x".repeat(33))) {
            HttpResponse<String> response = get("/api/v1/problems?" + query);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(response.body()).isEmpty();
        }
        assertThat(get("/error").statusCode()).isEqualTo(401);
        HttpResponse<String> session = get("/api/v1/auth/session");
        String csrf = JsonPath.read(session.body(), "$.csrf.token");
        String cookie = session.headers().allValues("set-cookie").stream()
                .map(value -> value.substring(0, value.indexOf(';'))).collect(java.util.stream.Collectors.joining("; "));
        for (String path : List.of("/api/v1/problems", "/api/v1/problem-tags")) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri(path))
                    .header("Origin", origin()).header("Cookie", cookie).header("X-CSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
        }
    }

    @Test
    void apiCannotWriteMetadataAndWorkerCannotReadTagsOrAccountData() throws Exception {
        JdbcTemplate api = database("forgeoj_api", "m0-api-test-secret");
        JdbcTemplate worker = database("forgeoj_worker", "m0-worker-test-secret");
        assertThat(api.queryForObject("SELECT COUNT(*) FROM problem_tag", Integer.class)).isEqualTo(9);
        for (String sql : List.of("INSERT INTO problem_tag VALUES (1, 'forbidden')",
                "UPDATE problem_tag SET tag='forbidden' WHERE problem_id=101",
                "DELETE FROM problem_tag WHERE problem_id=101",
                "UPDATE problem SET difficulty='HARD' WHERE id=1",
                "SELECT * FROM problem_test_case")) {
            assertThatThrownBy(() -> api.execute(sql)).as(sql).isInstanceOf(DataAccessException.class);
        }
        for (String sql : List.of("SELECT * FROM problem_tag", "SELECT * FROM user_account", "SELECT * FROM login_session")) {
            assertThatThrownBy(() -> worker.execute(sql)).as(sql).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> migrator().update("UPDATE problem SET difficulty='easy' WHERE id=1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> migrator().update("INSERT INTO problem_tag VALUES (1, ' ')"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void realDatabaseFailuresReturnEmpty503InsteadOfErrorDispatch401() throws Exception {
        JdbcTemplate db = migrator();
        db.execute("REVOKE SELECT ON forgeoj.problem_tag FROM 'forgeoj_api'@'%'");
        try {
            for (String path : List.of("/api/v1/problems", "/api/v1/problem-tags")) {
                HttpResponse<String> response = get(path);
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(response.body()).isEmpty();
            }
        } finally {
            db.execute("GRANT SELECT ON forgeoj.problem_tag TO 'forgeoj_api'@'%'");
        }
        db.execute("REVOKE SELECT ON forgeoj.problem FROM 'forgeoj_api'@'%'");
        try {
            HttpResponse<String> response = get("/api/v1/problems/sum-two-integers");
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).isEmpty();
        } finally {
            db.execute("GRANT SELECT ON forgeoj.problem TO 'forgeoj_api'@'%'");
        }
    }

    private static void problem(JdbcTemplate db, long id, String slug, String title, String difficulty,
            String status, boolean currentVersion) {
        db.update("""
                INSERT INTO problem (id, slug, title, statement_text, input_description, output_description,
                    public_samples_json, status, difficulty)
                VALUES (?, ?, ?, 'original fixture statement', 'fixture input', 'fixture output',
                    JSON_ARRAY(JSON_OBJECT('input', '1 2\\n', 'output', '2\\n')), ?, ?)
                """, id, slug, title, status, difficulty);
        if (currentVersion) {
            db.update("""
                    INSERT INTO problem_judge_version (id, problem_id, version_no, time_limit_ms, memory_limit_mb,
                        output_limit_bytes, comparison_rule_version, sandbox_policy_version, java_image_digest,
                        test_dataset_sha256)
                    SELECT ?, ?, 1, time_limit_ms, memory_limit_mb, output_limit_bytes, comparison_rule_version,
                        sandbox_policy_version, java_image_digest, test_dataset_sha256
                    FROM problem_judge_version WHERE id=1
                    """, id, id);
            db.update("UPDATE problem SET current_judge_version_id=? WHERE id=?", id, id);
        }
    }

    private JdbcTemplate migrator() { return database("forgeoj_migrator", "m0-migrator-test-secret"); }
    private JdbcTemplate database(String user, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), user, password));
    }
    private String origin() { return "http://localhost:" + port; }
    private URI uri(String path) { return URI.create(origin() + path); }
    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static Map<String, Object> object(String json) { return JsonPath.read(json, "$"); }
    private static List<String> slugs(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.items[*].slug");
    }
}
