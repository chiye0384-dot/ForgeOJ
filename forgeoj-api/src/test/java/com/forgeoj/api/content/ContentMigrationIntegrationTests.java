/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;

@Testcontainers
class ContentMigrationIntegrationTests {
    @Container static final MySQLContainer MYSQL=new MySQLContainer(DockerImageName.parse(
        "container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
        .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret")
        .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @Test void v9KeepsAllEighteenExistingTablesAndAddsNoAuthoringData() throws Exception {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret");var db=new JdbcTemplate(source);
        flyway("8").migrate();for(String script:List.of("db/devdata/R__seed_m0_development_data.sql","db/devdata/R__seed_m2_library_metadata.sql")) {
            try(var connection=source.getConnection()) {ScriptUtils.executeSqlScript(connection,new ClassPathResource(script));}
        }
        String personal=UUID.randomUUID().toString(),official=UUID.randomUUID().toString();
        db.update("INSERT INTO personal_problem_list(id,owner_id,title) VALUES(?,1,'private old fixture')",personal);
        db.update("INSERT INTO personal_problem_list_item(id,list_id,problem_id,position) VALUES(?,?,1,1)",UUID.randomUUID().toString(),personal);
        db.update("INSERT INTO official_problem_list(id,title) VALUES(?,'official old fixture')",official);
        db.update("INSERT INTO official_problem_list_item(id,list_id,problem_id,position) VALUES(?,?,1,1)",UUID.randomUUID().toString(),official);
        db.update("INSERT INTO user_code_draft(user_id,problem_id,language,source_code) VALUES(1,1,'JAVA_21','old incomplete code')");
        var before=facts(db);var upgrade=flyway("9");assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(facts(db)).isEqualTo(before);for(String table:List.of("authored_problem_draft","authored_problem_test_case")) assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();assertThat(facts(db)).isEqualTo(before);
    }
    private Flyway flyway(String target) {return Flyway.configure().dataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret").locations("classpath:db/migration").target(target).load();}
    private static Map<String,List<Map<String,Object>>> facts(JdbcTemplate db) {
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("user_account","user_judge_quota_lock","login_session","refresh_token","account_action_token","problem","problem_tag","problem_judge_version","problem_test_case","submission","judge_task","judge_task_attempt","outbox_event","personal_problem_list","personal_problem_list_item","official_problem_list","official_problem_list_item","user_code_draft")) {
            result.put(table,db.queryForList("SELECT * FROM "+table+" ORDER BY 1,2").stream().map(row -> {var normalized=new LinkedHashMap<String,Object>();row.forEach((k,v)->normalized.put(k,v instanceof byte[] bytes?HexFormat.of().formatHex(bytes):v));return (Map<String,Object>)normalized;}).toList());
        }return result;
    }
}
