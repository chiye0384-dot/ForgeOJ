package com.forgeoj.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

@Testcontainers
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "spring.rabbitmq.listener.direct.auto-startup=false"
        })
class MySqlMigrationIntegrationTests {

    private static final String MYSQL_IMAGE =
            "container-registry.oracle.com/mysql/community-server:8.4.12"
                    + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be";
    private static final String API_PASSWORD = "m0-api-test-secret";
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
    private DataSource apiDataSource;

    @Test
    void migratesAllM0TablesAndEnforcesDatabaseReadBoundaries() throws Exception {
        JdbcTemplate api = new JdbcTemplate(apiDataSource);
        JdbcTemplate migrator =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                MYSQL.getJdbcUrl(), "forgeoj_migrator", MIGRATOR_PASSWORD));

        List<String> tableNames =
                migrator.queryForList(
                        """
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'forgeoj'
                        ORDER BY table_name
                        """,
                        String.class);

        assertThat(tableNames)
                .contains(
                        "flyway_schema_history",
                        "judge_task",
                        "outbox_event",
                        "problem",
                        "problem_judge_version",
                        "problem_test_case",
                        "submission",
                        "user_account");

        assertThat(api.queryForObject("SELECT COUNT(*) FROM problem", Integer.class)).isZero();
        assertThat(api.queryForObject("SELECT COUNT(*) FROM user_account", Integer.class)).isZero();
        String verdictConstraint =
                migrator.queryForObject(
                        """
                        SELECT check_clause
                        FROM information_schema.check_constraints
                        WHERE constraint_schema = 'forgeoj'
                          AND constraint_name = 'chk_submission_verdict'
                        """,
                        String.class);
        assertThat(verdictConstraint).contains("OLE");
        assertThatThrownBy(
                        () ->
                                api.queryForObject(
                                        "SELECT COUNT(*) FROM problem_test_case", Integer.class))
                .isInstanceOf(DataAccessException.class);

        try (var workerConnection =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(), "forgeoj_worker", WORKER_PASSWORD);
                var workerStatement = workerConnection.createStatement();
                var hiddenTestCount =
                        workerStatement.executeQuery("SELECT COUNT(*) FROM problem_test_case")) {
            assertThat(hiddenTestCount.next()).isTrue();
            assertThat(hiddenTestCount.getInt(1)).isZero();
            assertThatThrownBy(
                            () ->
                                    workerStatement.executeQuery(
                                            "SELECT password_hash FROM user_account"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
