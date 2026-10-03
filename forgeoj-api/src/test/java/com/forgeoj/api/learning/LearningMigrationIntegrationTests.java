/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.learning;

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
class LearningMigrationIntegrationTests {
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse(
            "container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret")
            .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @Test void v8PreservesEveryOldTableAndSeedsNoLearningFacts() throws Exception {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret");
        var db=new JdbcTemplate(source);
        flyway("7").migrate();
        for(String script:List.of("db/devdata/R__seed_m0_development_data.sql","db/devdata/R__seed_m2_library_metadata.sql")) {
            try(var connection=source.getConnection()) {ScriptUtils.executeSqlScript(connection,new ClassPathResource(script));}
        }
        seedFacts(db);
        var before=facts(db);
        var upgrade=flyway("8");
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(facts(db)).isEqualTo(before);
        for(String table:List.of("personal_problem_list","personal_problem_list_item","official_problem_list","official_problem_list_item","user_code_draft"))
            assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
        assertThat(facts(db)).isEqualTo(before);
    }
    private Flyway flyway(String target) {return Flyway.configure().dataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret").locations("classpath:db/migration").target(target).load();}
    private static Map<String,List<Map<String,Object>>> facts(JdbcTemplate db) {
        Map<String,List<Map<String,Object>>> result=new LinkedHashMap<>();
        for(String table:List.of("user_account","user_judge_quota_lock","login_session","refresh_token","account_action_token","problem","problem_tag","problem_judge_version","problem_test_case","submission","judge_task","judge_task_attempt","outbox_event")) {
            result.put(table,db.queryForList("SELECT * FROM "+table+" ORDER BY 1,2").stream().map(row->{
                Map<String,Object> normalized=new LinkedHashMap<>();row.forEach((key,value)->normalized.put(key,value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value));return normalized;
            }).toList());
        }
        return result;
    }
    private static void seedFacts(JdbcTemplate db) {
        db.update("UPDATE user_account SET email='learning-fixture@example.test',email_verified_at=CURRENT_TIMESTAMP(6) WHERE id=1");
        db.update("INSERT INTO login_session(id,user_id,expires_at) VALUES(?,1,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY))","00000000-0000-0000-0000-000000000002");
        db.update("INSERT INTO refresh_token(token_sha256,session_id) VALUES(REPEAT('2',64),?)","00000000-0000-0000-0000-000000000002");
        db.update("INSERT INTO account_action_token(token_sha256,user_id,purpose,target_email,expires_at) VALUES(REPEAT('3',64),1,'RESET_PASSWORD','learning-fixture@example.test',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY))");
        db.update("INSERT INTO submission(id,user_id,problem_id,judge_version_id,client_request_id,language,source_code,source_sha256,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256,processing_status,status_version) SELECT ?,1,1,1,?,'JAVA_21','preserved source',REPEAT('a',64),time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256,'RUNNING',1 FROM problem_judge_version WHERE id=1","00000000-0000-0000-0000-000000000003","00000000-0000-0000-0000-000000000004");
        db.update("INSERT INTO judge_task(id,submission_id,task_type,contract_version,task_status,status_version,attempt_count) VALUES(?,?,'JUDGE_SUBMISSION',1,'RUNNING',1,1)","00000000-0000-0000-0000-000000000005","00000000-0000-0000-0000-000000000003");
        db.update("INSERT INTO judge_task_attempt(id,judge_task_id,attempt_no,lease_token,worker_id,attempt_status,lease_expires_at) VALUES(?,?,1,?,'learning-fixture-worker','RUNNING',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE))","00000000-0000-0000-0000-000000000007","00000000-0000-0000-0000-000000000005","00000000-0000-0000-0000-000000000008");
        db.update("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,contract_version,payload) VALUES(?,'JUDGE_TASK',?,'JUDGE_TASK_QUEUED',1,JSON_OBJECT('fixture','preserved'))","00000000-0000-0000-0000-000000000006","00000000-0000-0000-0000-000000000005");
    }
}
