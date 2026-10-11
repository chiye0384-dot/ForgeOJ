/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.observability;
import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;
@Testcontainers @SpringBootTest(properties={"spring.flyway.locations=classpath:db/migration,classpath:db/devdata","forgeoj.assignments.scheduler.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false"})
class MonitoringSnapshotIntegrationTests {
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
        .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret").withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",()->"forgeoj_api");r.add("spring.datasource.password",()->"m0-api-test-secret");r.add("spring.flyway.url",MYSQL::getJdbcUrl);r.add("spring.flyway.user",()->"forgeoj_migrator");r.add("spring.flyway.password",()->"m0-migrator-test-secret");}
    @Autowired MonitoringMapper mapper;@Autowired PlatformTransactionManager manager;
    @Test void realLeastPrivilegeAggregationIsReadOnlyAndDoesNotExposeFixturePayloads(){
        var db=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret"));
        var problem=db.queryForMap("SELECT id,current_judge_version_id FROM problem WHERE scope='PUBLIC' AND current_judge_version_id IS NOT NULL ORDER BY id LIMIT 1");
        long p=((Number)problem.get("id")).longValue(),j=((Number)problem.get("current_judge_version_id")).longValue();
        String username="monitor_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        db.update("INSERT INTO user_account(username,password_hash,status) VALUES(?,'fixture-not-login-hash','ACTIVE')",username);
        long owner=db.queryForObject("SELECT id FROM user_account WHERE username=?",Long.class,username);
        String submission=UUID.randomUUID().toString(),task=UUID.randomUUID().toString();
        db.update("INSERT INTO submission(id,user_id,problem_id,judge_version_id,client_request_id,language,source_code,source_sha256,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256,processing_status,diagnostic_message,finished_at) VALUES(?,?,?,?,?,'JAVA_21','MONITOR_PRIVATE_SOURCE',REPEAT('b',64),2000,256,65536,'trim-trailing-whitespace-v1','m0-v1','fixture-image',REPEAT('a',64),'SYSTEM_ERROR','MONITOR_PRIVATE_DIAGNOSTIC',CURRENT_TIMESTAMP(6))",submission,owner,p,j,UUID.randomUUID().toString());
        db.update("INSERT INTO judge_task(id,submission_id,task_type,contract_version,task_status,attempt_count,max_attempts,last_failure_code,finished_at) VALUES(?,?,'JUDGE_SUBMISSION',1,'DEAD_LETTER',2,2,'PLATFORM_FAILURE',CURRENT_TIMESTAMP(6))",task,submission);
        db.update("INSERT INTO cache_invalidation_outbox(namespace,old_revision,attempts,error_code) VALUES('public',999991,1,'MONITOR_PRIVATE_FAILURE')");
        // A real accepted task has unsigned attempt_count=0 until the Worker claims it.
        String queuedSubmission=UUID.randomUUID().toString(),queuedTask=UUID.randomUUID().toString();
        db.update("INSERT INTO submission(id,user_id,problem_id,judge_version_id,client_request_id,language,source_code,source_sha256,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256,processing_status) SELECT ?,user_id,problem_id,judge_version_id,?,language,source_code,source_sha256,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256,'QUEUED' FROM submission WHERE id=?",queuedSubmission,UUID.randomUUID().toString(),submission);
        db.update("INSERT INTO judge_task(id,submission_id,task_type,contract_version,task_status,attempt_count,max_attempts) VALUES(?,?,'JUDGE_SUBMISSION',1,'QUEUED',0,3)",queuedTask,queuedSubmission);
        var before=db.queryForMap("SELECT task_status,attempt_count,max_attempts FROM judge_task WHERE id=?",task);
        var s=new MonitoringSnapshot(mapper,manager);s.refresh();assertThat(s.fresh()).isTrue();String text=s.render();
        assertThat(text).contains("kind=\"FORMAL\",state=\"DEAD_LETTER\"} 1","forgeoj_attempt_retries{kind=\"FORMAL\"} 1.0","forgeoj_invalidation_pending 1","forgeoj_invalidation_failed 1").doesNotContain(task,submission,username,"MONITOR_PRIVATE","sum-two","fixture-image","m0-api-test-secret");
        assertThat(text).contains("kind=\"FORMAL\",state=\"QUEUED\"} 1").doesNotContain(queuedTask,queuedSubmission);
        assertThat(db.queryForObject("SELECT attempt_count FROM judge_task WHERE id=?",Integer.class,queuedTask)).isZero();
        // Clamp before unsigned subtraction, including an observation with no positive gap.
        db.update("UPDATE public_search_control SET readable_epoch=(SELECT revision+1 FROM cache_epoch WHERE namespace='public') WHERE id=1");
        s.refresh();assertThat(s.fresh()).isTrue();assertThat(s.render()).contains("forgeoj_search_epoch_gap 0");
        assertThat(db.queryForMap("SELECT task_status,attempt_count,max_attempts FROM judge_task WHERE id=?",task)).isEqualTo(before);
        var worker=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_worker","m0-worker-test-secret"));assertThatThrownBy(()->worker.queryForObject("SELECT COUNT(*) FROM cache_invalidation_outbox",Long.class)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM submission WHERE id=? AND source_code='MONITOR_PRIVATE_SOURCE' AND diagnostic_message='MONITOR_PRIVATE_DIAGNOSTIC'",Long.class,submission)).isEqualTo(1L);
    }
}
