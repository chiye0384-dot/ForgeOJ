package com.forgeoj.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class QuotaMigrationUpgradeIntegrationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12"
                    + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("forgeoj")
            .withUsername("bootstrap")
            .withPassword("bootstrap-test-secret")
            .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),
                    "/docker-entrypoint-initdb.d/01-init-test-users.sql");

    @Test
    void upgradingExistingV3AccountsBackfillsQuotaLocksWithoutChangingAccounts() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), "forgeoj_migrator", "m0-migrator-test-secret")
                .locations("classpath:db/migration").target("3").load().migrate();
        JdbcTemplate migrator = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), "forgeoj_migrator", "m0-migrator-test-secret"));
        migrator.update("""
                INSERT INTO user_account (id, username, password_hash, status)
                VALUES (11, 'upgrade-active', 'fixture-hash', 'ACTIVE'),
                       (12, 'upgrade-disabled', 'fixture-hash', 'DISABLED')
                """);
        var accountsBefore = migrator.queryForList("SELECT * FROM user_account ORDER BY id");
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), "forgeoj_migrator", "m0-migrator-test-secret")
                .locations("classpath:db/migration").target("4").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(migrator.queryForList("SELECT user_id FROM user_judge_quota_lock ORDER BY user_id",
                Long.class)).containsExactly(11L, 12L);
        assertThat(migrator.queryForList("SELECT * FROM user_account ORDER BY id"))
                .isEqualTo(accountsBefore);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
