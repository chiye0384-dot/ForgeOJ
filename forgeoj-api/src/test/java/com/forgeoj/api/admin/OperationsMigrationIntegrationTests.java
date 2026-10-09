/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;

@Testcontainers
class OperationsMigrationIntegrationTests {
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
        .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret")
        .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @Test void upgradesPreserveLegacyFactsAndAddNoSyntheticRecoveries() {
        flyway("19").migrate();var db=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret"));
        db.update("INSERT INTO user_account(id,username,password_hash,status) VALUES(1,'old_ops_fixture','unusable fixture hash','ACTIVE')");
        db.update("INSERT INTO user_judge_quota_lock(user_id) VALUES(1)");
        String draft=UUID.randomUUID().toString();db.update("INSERT INTO authored_problem_draft(id,owner_id,title,metadata_json,reference_code,solution_idea,solution_code) VALUES(?,1,'old fixture','{}','old reference','old idea','old solution')",draft);
        var before=facts(db);var upgrade=flyway("22");assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(3);assertThat(facts(db)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM operations_recovery_request",Integer.class)).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM admin_account",Integer.class)).isZero();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();assertThat(facts(db)).isEqualTo(before);
    }
    private Flyway flyway(String target){return Flyway.configure().dataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret").locations("classpath:db/migration").target(target).load();}
    private Map<String,List<Map<String,Object>>> facts(JdbcTemplate db){var result=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:List.of("user_account","user_judge_quota_lock","authored_problem_draft","submission","judge_task","judge_task_attempt","outbox_event","content_validation_job","content_validation_attempt","self_test_job","self_test_attempt","content_review","classroom_assignment","assignment_attempt"))result.put(table,db.queryForList("SELECT * FROM "+table+" ORDER BY 1"));return result;}
}
