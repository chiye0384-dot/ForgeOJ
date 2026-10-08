/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

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
class AssignmentMigrationIntegrationTests {
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse(
        "container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
        .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret")
        .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @Test void v17PreservesAllExistingBytesAndClassroomPrivateFacts() throws Exception {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret");var db=new JdbcTemplate(source);
        flyway("16").migrate();for(String script:List.of("db/devdata/R__seed_m0_development_data.sql","db/devdata/R__seed_m2_library_metadata.sql")) {
            try(var connection=source.getConnection()) {ScriptUtils.executeSqlScript(connection,new ClassPathResource(script));}
        }
        String personal=UUID.randomUUID().toString(),official=UUID.randomUUID().toString();
        db.update("INSERT INTO personal_problem_list(id,owner_id,title) VALUES(?,1,'private old fixture')",personal);
        db.update("INSERT INTO personal_problem_list_item(id,list_id,problem_id,position) VALUES(?,?,1,1)",UUID.randomUUID().toString(),personal);
        db.update("INSERT INTO official_problem_list(id,title) VALUES(?,'official old fixture')",official);
        db.update("INSERT INTO official_problem_list_item(id,list_id,problem_id,position) VALUES(?,?,1,1)",UUID.randomUUID().toString(),official);
        db.update("INSERT INTO user_code_draft(user_id,problem_id,language,source_code) VALUES(1,1,'JAVA_21','old incomplete code')");
        String authored=UUID.randomUUID().toString();
        db.update("INSERT INTO authored_problem_draft(id,owner_id,title,metadata_json,reference_code,solution_idea,solution_code) VALUES(?,1,'old private author',JSON_OBJECT(),'old reference','old idea','old independent solution')",authored);
        db.update("INSERT INTO authored_problem_test_case(draft_id,sequence_no,input_gzip,expected_output_gzip,input_bytes,expected_output_bytes,input_sha256,expected_output_sha256) VALUES(?,1,X'001122',X'334455',0,0,REPEAT('a',64),REPEAT('b',64))",authored);
        db.update("INSERT INTO official_problem_solution(judge_version_id,idea,language,source_code) VALUES(1,'old official idea','JAVA_21','old independent official code')");db.update("INSERT INTO user_solution_early_view(user_id,judge_version_id) VALUES(1,1)");String snapshot=UUID.randomUUID().toString(),job=UUID.randomUUID().toString();
        db.update("INSERT INTO content_validation_snapshot(id,draft_id,owner_id,draft_version,metadata_text,reference_code,solution_idea,solution_code,reference_sha256,solution_sha256,test_dataset_sha256,snapshot_sha256,java_image_digest,time_limit_ms,memory_limit_mb,output_limit_bytes) VALUES(?,?,1,1,'{}','old immutable reference','old immutable idea','old immutable solution',REPEAT('a',64),REPEAT('b',64),REPEAT('c',64),REPEAT('d',64),'old pinned image',2000,256,1048576)",snapshot,authored);
        db.update("INSERT INTO content_validation_test_case(snapshot_id,sequence_no,input_gzip,expected_output_gzip,input_bytes,expected_output_bytes,input_sha256,expected_output_sha256) VALUES(?,1,X'112233',X'445566',0,0,REPEAT('a',64),REPEAT('b',64))",snapshot);
        db.update("INSERT INTO content_validation_job(id,snapshot_id,owner_id,client_request_id) VALUES(?,?,1,?)",job,snapshot,UUID.randomUUID().toString());
        db.update("UPDATE content_validation_job SET processing_status='FINISHED',validation_status='PASSED',reference_result='ACCEPTED',solution_result='ACCEPTED',finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",job);
        db.update("INSERT INTO content_review(id,draft_id,owner_id,draft_version,validation_job_id,snapshot_id,request_id,review_no) VALUES(?,?,1,1,?,?,?,1)",UUID.randomUUID().toString(),authored,job,snapshot,UUID.randomUUID().toString());
        String selfSnapshot=UUID.randomUUID().toString(),selfJob=UUID.randomUUID().toString();
        db.update("INSERT INTO self_test_snapshot(id,owner_id,problem_id,problem_slug,judge_version_id,language,source_sha256,input_sha256,input_bytes,time_limit_ms,memory_limit_mb,output_limit_bytes,java_image_digest,comparison_rule_version,sandbox_policy_version,snapshot_sha256) SELECT ?,1,1,'sum-two-integers',1,'JAVA_21',REPEAT('a',64),REPEAT('b',64),0,time_limit_ms,memory_limit_mb,output_limit_bytes,java_image_digest,comparison_rule_version,sandbox_policy_version,REPEAT('c',64) FROM problem_judge_version WHERE id=1",selfSnapshot);
        db.update("INSERT INTO self_test_payload(snapshot_id,source_code,input_text) VALUES(?,'old frozen self test','')",selfSnapshot);
        db.update("INSERT INTO self_test_job(id,snapshot_id,owner_id,client_request_id,processing_status,execution_result,finished_at,expires_at) VALUES(?,?,1,?,'FINISHED','SUCCESS',CURRENT_TIMESTAMP(6),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY))",selfJob,selfSnapshot,UUID.randomUUID().toString());
        db.update("INSERT INTO self_test_attempt(id,job_id,attempt_no,lease_token,worker_id,attempt_status,lease_expires_at,finished_at) VALUES(?,?,1,?,'old-self-worker','SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",UUID.randomUUID().toString(),selfJob,UUID.randomUUID().toString());
        db.update("INSERT INTO self_test_output(job_id,snapshot_id,output_gzip,output_bytes,output_sha256) VALUES(?,?,X'112233',0,REPEAT('d',64))",selfJob,selfSnapshot);
        String room=UUID.randomUUID().toString();db.update("INSERT INTO classroom(id,owner_id,title) VALUES(?,1,'old classroom')",room);db.update("INSERT INTO classroom_member(classroom_id,user_id,role,status) VALUES(?,1,'OWNER','ACTIVE')",room);
        db.update("UPDATE problem SET scope='CLASSROOM',classroom_id=? WHERE id=1",room);
        db.update("INSERT INTO classroom_problem(problem_id,classroom_id,created_by,draft_id,draft_version,validation_job_id,snapshot_id,client_request_id,solution_policy) VALUES(1,?,1,?,1,?,?,?,'AFTER_AC')",room,authored,job,snapshot,UUID.randomUUID().toString());
        db.update("INSERT INTO classroom_solution_early_view(user_id,problem_id) VALUES(1,1)");
        var before=facts(db);var upgrade=flyway("17");assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(facts(db)).isEqualTo(before);for(String table:List.of("classroom_assignment","assignment_problem","assignment_draft_problem","assignment_participant","assignment_attempt","assignment_precompletion","assignment_self_test")) assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT scope FROM problem WHERE id=1",String.class)).isEqualTo("CLASSROOM");
        assertThat(upgrade.migrate().migrationsExecuted).isZero();assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();assertThat(facts(db)).isEqualTo(before);
    }
    private Flyway flyway(String target) {return Flyway.configure().dataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret").locations("classpath:db/migration").target(target).load();}
    private static Map<String,List<Map<String,Object>>> facts(JdbcTemplate db) {
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("user_account","user_judge_quota_lock","login_session","refresh_token","account_action_token","problem","problem_tag","problem_judge_version","problem_test_case","submission","judge_task","judge_task_attempt","outbox_event","personal_problem_list","personal_problem_list_item","official_problem_list","official_problem_list_item","user_code_draft","authored_problem_draft","authored_problem_test_case","official_problem_solution","user_solution_early_view","content_validation_snapshot","content_validation_test_case","content_validation_job","content_validation_attempt","content_review","content_output_preview_case","content_output_acceptance","self_test_snapshot","self_test_payload","self_test_job","self_test_attempt","self_test_output","classroom","classroom_member","classroom_transfer","classroom_creation_request","classroom_problem","classroom_solution_early_view")) {
            result.put(table,db.queryForList("SELECT * FROM "+table+" ORDER BY 1,2").stream().map(row -> {var normalized=new LinkedHashMap<String,Object>();row.forEach((k,v)->{normalized.put(k,v instanceof byte[] bytes?HexFormat.of().formatHex(bytes):v);});return (Map<String,Object>)normalized;}).toList());
        }return result;
    }
}
