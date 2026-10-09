/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.*;
import java.sql.DriverManager;
import java.util.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"forgeoj.assignments.scheduler.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false","spring.rabbitmq.listener.direct.auto-startup=false"})
class OperationsIntegrationTests {
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql")).withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret").withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",()->"forgeoj_api");r.add("spring.datasource.password",()->"m0-api-test-secret");r.add("spring.flyway.url",MYSQL::getJdbcUrl);r.add("spring.flyway.user",()->"forgeoj_migrator");r.add("spring.flyway.password",()->"m0-migrator-test-secret");}
    @LocalServerPort int port;
    @MockitoSpyBean AdminMapper admins;
    @org.springframework.beans.factory.annotation.Autowired OperationsRecoveryMapper recovery;
    static final String PASSWORD="operations-initial-fixture",NEXT="operations-changed-fixture";
    final HttpClient client=HttpClient.newHttpClient();
    @BeforeEach void seed() throws Exception {
        reset(admins);var db=db();
        for(String t:List.of("operations_recovery_request","admin_audit_event","admin_creation_request","admin_refresh_token","admin_login_session","admin_account"))db.execute("DELETE FROM "+t);
        db.update("UPDATE submission SET processing_status='CANCELLED',status_version=status_version+1,finished_at=CURRENT_TIMESTAMP(6) WHERE user_id=1 AND processing_status='QUEUED'");
        db.update("UPDATE content_validation_job SET processing_status='SYSTEM_ERROR',status_version=status_version+1,finished_at=CURRENT_TIMESTAMP(6) WHERE owner_id=1 AND processing_status='QUEUED'");
        db.update("UPDATE self_test_job SET processing_status='CANCELLED',status_version=status_version+1,finished_at=CURRENT_TIMESTAMP(6),expires_at=TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP(6)) WHERE owner_id=1 AND processing_status='QUEUED'");
        db.execute("ALTER TABLE admin_account AUTO_INCREMENT=1");db.update("UPDATE admin_policy_fence SET bootstrapped=FALSE WHERE id=1");
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret")){AdminProvisioning.bootstrap(c,"root_ops",PASSWORD,new BCryptPasswordEncoder());}
        db.update("INSERT INTO user_account(id,username,password_hash,status) VALUES(1,'ordinary_ops',?,'ACTIVE') ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash),status='ACTIVE'",new BCryptPasswordEncoder().encode(PASSWORD));db.update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(1)");
    }
    @Test void independentRolesAndCurrentRevocationProtectOperationsRoutes()throws Exception{
        var root=ready("root_ops");var opId=create(root,"limited_ops","OPS_ADMIN");create(root,"limited_review","CONTENT_REVIEWER");
        var ops=ready("limited_ops");var reviewer=ready("limited_review");
        code(get(root,"/operations/tasks"),200);code(get(ops,"/operations/tasks"),200);
        code(get(reviewer,"/operations/tasks"),403);code(get(new Browser(),"/operations/tasks"),401);
        var user=new Browser();code(send(user,"GET","/api/v1/auth/session",null),200);code(send(user,"POST","/api/v1/auth/login",Map.of("username","ordinary_ops","password",PASSWORD)),200);
        code(get(user,"/operations/tasks"),403);
        code(get(reviewer,"/operations/tasks/FORMAL/"+UUID.randomUUID()+"/attempts"),403);
        code(get(reviewer,"/operations/tasks/FORMAL/"+UUID.randomUUID()+"/events"),403);
        long version=db().queryForObject("SELECT version FROM admin_account WHERE id=?",Long.class,opId);
        code(write(root,"/accounts/"+opId+"/disable",Map.of("expectedVersion",version,"reason","revoke ops fixture")),200);
        code(get(ops,"/operations/tasks"),401);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_audit_event WHERE action='ADMIN_ACCESS_DENIED'",Integer.class)).isGreaterThanOrEqualTo(5);
    }
    // Reviewer and ordinary identities never gain operations execution authority.
    @Test void executionRoutesRejectReviewerAndOrdinary()throws Exception{
        var root=ready("root_ops");create(root,"limited_review","CONTENT_REVIEWER");var reviewer=ready("limited_review");
        var user=new Browser();code(send(user,"GET","/api/v1/auth/session",null),200);code(send(user,"POST","/api/v1/auth/login",Map.of("username","ordinary_ops","password",PASSWORD)),200);
        var body=Map.of("expectedVersion",0,"clientRequestId",UUID.randomUUID().toString(),"reason","permission fixture");
        code(write(reviewer,"/operations/tasks/FORMAL/"+UUID.randomUUID()+"/retry",body),403);code(write(user,"/operations/tasks/FORMAL/"+UUID.randomUUID()+"/retry",body),403);
    }
    @Test void readAuditFailureReturnsNoMetadata()throws Exception{
        var root=ready("root_ops");doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected audit failure")).when(admins).audit(any());
        try{var r=get(root,"/operations/tasks");code(r,503);assertThat(r.body()).isEmpty();verify(admins,atLeastOnce()).audit(any());}finally{reset(admins);}
    }
    @Test void exactMetadataWhitelistPagingAndBoundAttemptsNeverReturnPrivateInputs()throws Exception{
        var root=ready("root_ops");String task=formalFixture();
        var list=get(root,"/operations/tasks?id="+task);code(list,200);
        assertThat(((List<?>)read(list,"$.items"))).hasSize(1);
        var detail=get(root,"/operations/tasks/FORMAL/"+task);code(detail,200);
        Map<String,Object> item=JsonPath.read(detail.body(),"$");
        assertThat(item).containsOnlyKeys("kind","id","ownerId","submissionId","snapshotId","status","version","attemptCount","maxAttempts","failureCode","createdAt","startedAt","finishedAt","nextAttemptAt","leaseExpired","expiresAt","executionRecoveryUsed");
        var attempts=get(root,"/operations/tasks/FORMAL/"+task+"/attempts");code(attempts,200);
        Map<String,Object> attempt=JsonPath.read(attempts.body(),"$.items[0]");assertThat(attempt).containsOnlyKeys("id","number","status","failureCode","startedAt","heartbeatAt","leaseExpiresAt","finishedAt");
        var events=get(root,"/operations/tasks/FORMAL/"+task+"/events");code(events,200);assertThat((List<?>)read(events,"$.items")).hasSize(1);
        Map<String,Object> event=JsonPath.read(events.body(),"$.items[0]");assertThat(event).containsOnlyKeys("id","type","sequence","publishAttempts","errorCode","nextAttemptAt","lastAttemptAt","failedAt","publishedAt","deliveryRecoveryUsed");
        for(var r:List.of(list,detail,attempts,events)){assertThat(r.body()).doesNotContain("OPS_PRIVATE_SOURCE","OPS_PRIVATE_DIAGNOSTIC","OPS_PRIVATE_ATTEMPT","leaseToken","workerId","payload","passwordHash");assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");}
        code(get(root,"/operations/tasks?size=51"),400);code(get(root,"/operations/tasks?kind=ARBITRARY_SQL"),400);code(get(root,"/operations/tasks/FORMAL/"+UUID.randomUUID()),404);
        code(get(root,"/operations/tasks/FORMAL/"+task+"/attempts?page=2&size=1"),200);
    }
    @Test void originalTaskRetryKeepsInputsAttemptsAndLifetimeBudgetAndReplaysAfterResponseLoss()throws Exception{
        var root=ready("root_ops");String task=formalFixture(),path="/operations/tasks/FORMAL/"+task+"/retry";var db=db();
        var before=db.queryForMap("SELECT s.id,s.source_code,s.source_sha256,s.judge_version_id,s.created_at FROM submission s JOIN judge_task j ON j.submission_id=s.id WHERE j.id=?",task);
        var oldAttempt=db.queryForMap("SELECT * FROM judge_task_attempt WHERE judge_task_id=?",task);
        var body=retryBody(0);var first=write(root,path,body);code(first,200);var replay=write(root,path,body);code(replay,200);assertThat(replay.body()).isEqualTo(first.body());
        assertThat(db.queryForMap("SELECT s.id,s.source_code,s.source_sha256,s.judge_version_id,s.created_at FROM submission s JOIN judge_task j ON j.submission_id=s.id WHERE j.id=?",task)).isEqualTo(before);
        assertThat(db.queryForMap("SELECT * FROM judge_task_attempt WHERE judge_task_id=?",task)).isEqualTo(oldAttempt);
        assertThat(db.queryForMap("SELECT task_status,attempt_count,max_attempts,status_version FROM judge_task WHERE id=?",task)).containsEntry("task_status","QUEUED").containsEntry("attempt_count",1L).containsEntry("max_attempts",2L);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='JUDGE_TASK_QUEUED' AND sequence_no=2",Integer.class,task)).isEqualTo(1);
        var changed=new HashMap<>(body);changed.put("reason","changed request");code(write(root,path,changed),409);
        db.update("UPDATE judge_task SET task_status='DEAD_LETTER',attempt_count=2,status_version=status_version+1,finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",task);
        db.update("UPDATE submission s JOIN judge_task j ON j.submission_id=s.id SET s.processing_status='SYSTEM_ERROR',s.finished_at=CURRENT_TIMESTAMP(6) WHERE j.id=?",task);
        code(write(root,path,retryBody(2)),409);code(write(root,path,body),200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM operations_recovery_request WHERE task_id=?",Integer.class,task)).isEqualTo(1);
    }
    @Test void concurrentDistinctRequestsHaveOneWinner()throws Exception{
        var root=ready("root_ops");String task=formalFixture(),path="/operations/tasks/FORMAL/"+task+"/retry";
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var gate=new java.util.concurrent.CountDownLatch(1);var a=pool.submit(()->{gate.await();return write(root,path,retryBody(0)).statusCode();});var b=pool.submit(()->{gate.await();return write(root,path,retryBody(0)).statusCode();});gate.countDown();
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(db().queryForObject("SELECT COUNT(*) FROM operations_recovery_request WHERE task_id=?",Integer.class,task)).isEqualTo(1);
    }
    @Test void auditFailureRollsBackStateReceiptAndOutbox()throws Exception{
        var root=ready("root_ops");String task=formalFixture();var body=retryBody(0);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected recovery audit failure")).when(admins).audit(any());
        try{code(write(root,"/operations/tasks/FORMAL/"+task+"/retry",body),503);}finally{reset(admins);}
        assertThat(db().queryForMap("SELECT task_status,max_attempts FROM judge_task WHERE id=?",task)).containsEntry("task_status","DEAD_LETTER").containsEntry("max_attempts",1L);
        assertThat(db().queryForObject("SELECT status_version FROM judge_task WHERE id=?",Long.class,task)).isZero();
        assertThat(db().queryForObject("SELECT COUNT(*) FROM operations_recovery_request WHERE task_id=?",Integer.class,task)).isZero();
        assertThat(db().queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,task)).isEqualTo(1);
        code(write(root,"/operations/tasks/FORMAL/"+task+"/retry",body),200);
    }
    @Test void rejectsCorruptSnapshotsUserVerdictsActiveStatesStaleVersionsAndFullQuota()throws Exception{
        var root=ready("root_ops");var db=db();String task=formalFixture(),path="/operations/tasks/FORMAL/"+task+"/retry";
        code(write(root,path,retryBody(1)),409);
        var fractional=new HashMap<>(retryBody(0));fractional.put("expectedVersion",0.5);code(write(root,path,fractional),400);
        var extra=new HashMap<>(retryBody(0));extra.put("verdict","AC");code(write(root,path,extra),400);
        db.update("UPDATE judge_task SET last_failure_code='SNAPSHOT_INVALID' WHERE id=?",task);code(write(root,path,retryBody(0)),409);
        db.update("UPDATE judge_task SET task_status='FINISHED',last_failure_code=NULL WHERE id=?",task);code(write(root,path,retryBody(0)),409);
        db.update("UPDATE judge_task SET task_status='DEAD_LETTER',last_failure_code='PLATFORM_FAILURE' WHERE id=?",task);
        for(int i=0;i<3;i++){String queued=formalFixture();db.update("UPDATE submission s JOIN judge_task j ON j.submission_id=s.id SET s.processing_status='QUEUED',s.finished_at=NULL WHERE j.id=?",queued);}
        code(write(root,path,retryBody(0)),409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM operations_recovery_request WHERE task_id=?",Integer.class,task)).isZero();
    }
    @Test void failedDeliveryRearmsSameBoundEventOnceWithoutResettingCountsOrPayload()throws Exception{
        var root=ready("root_ops");String task=formalFixture();var db=db();String event=db.queryForObject("SELECT id FROM outbox_event WHERE aggregate_id=?",String.class,task);
        db.update("UPDATE outbox_event SET failed_at=CURRENT_TIMESTAMP(6),publish_attempts=5,last_error_code='UNROUTABLE' WHERE id=?",event);
        String payload=db.queryForObject("SELECT CAST(payload AS CHAR) FROM outbox_event WHERE id=?",String.class,event);
        String path="/operations/tasks/FORMAL/"+task+"/events/"+event+"/recover";var body=new HashMap<>(retryBody(0));body.put("expectedPublishAttempts",5);
        var first=write(root,path,body);code(first,200);var replay=write(root,path,body);code(replay,200);assertThat(replay.body()).isEqualTo(first.body());
        assertThat(db.queryForMap("SELECT failed_at,publish_attempts,CAST(payload AS CHAR) AS payload FROM outbox_event WHERE id=?",event)).containsEntry("failed_at",null).containsEntry("publish_attempts",5L).containsEntry("payload",payload);
        assertThat(db.queryForObject("SELECT previous_failure_code FROM operations_recovery_request WHERE target_id=?",String.class,event)).isEqualTo("UNROUTABLE");
        var history=get(root,"/operations/tasks/FORMAL/"+task+"/recoveries");code(history,200);
        Map<String,Object> row=JsonPath.read(history.body(),"$.items[0]");assertThat(row).containsOnlyKeys("id","scope","eventId","previousStatus","previousVersion","previousAttempts","previousMaxAttempts","previousFailureCode","previousFinishedAt","resultingVersion","createdAt").containsEntry("previousFailureCode","UNROUTABLE");
        db.update("UPDATE outbox_event SET failed_at=CURRENT_TIMESTAMP(6),publish_attempts=6 WHERE id=?",event);var next=new HashMap<>(retryBody(0));next.put("expectedPublishAttempts",6);code(write(root,path,next),409);
        String other=formalFixture(),invalid=db.queryForObject("SELECT id FROM outbox_event WHERE aggregate_id=?",String.class,other);
        db.update("UPDATE outbox_event SET failed_at=CURRENT_TIMESTAMP(6),publish_attempts=5,payload=JSON_SET(payload,'$.privateField','forbidden') WHERE id=?",invalid);
        code(write(root,"/operations/tasks/FORMAL/"+other+"/events/"+invalid+"/recover",body),409);
    }
    @Test void narrowDatabaseGrantsRejectVerdictInputsAttemptsLeasesAndWorkerReceipts(){
        var api=restricted("forgeoj_api","m0-api-test-secret");var worker=restricted("forgeoj_worker","m0-worker-test-secret");
        for(String sql:List.of("UPDATE submission SET verdict='AC' WHERE FALSE","UPDATE submission SET source_code='changed' WHERE FALSE","UPDATE judge_task SET attempt_count=0 WHERE FALSE","UPDATE judge_task SET lease_token=NULL WHERE FALSE","UPDATE content_validation_job SET validation_status='PASSED' WHERE FALSE","UPDATE self_test_job SET execution_result='SUCCESS' WHERE FALSE","UPDATE judge_task_attempt SET failure_code=NULL WHERE FALSE","UPDATE operations_recovery_request SET reason='changed' WHERE FALSE","DELETE FROM operations_recovery_request WHERE FALSE"))assertThatThrownBy(()->api.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->worker.queryForList("SELECT * FROM operations_recovery_request")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        for(String table:List.of("judge_task_attempt","content_validation_attempt","self_test_attempt"))assertThatThrownBy(()->api.queryForList("SELECT lease_token,worker_id FROM "+table+" LIMIT 0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    private Map<String,Object> retryBody(long version){return Map.of("expectedVersion",version,"clientRequestId",UUID.randomUUID().toString(),"reason","recover isolated platform failure");}
    @Test void contentRecoveryRequiresCurrentDraftAndPendingReviewLatestJobAndNeverAppliesPreview()throws Exception{
        var root=ready("root_ops");var db=db();String valid=contentFixture("VALIDATE"),preview=contentFixture("OUTPUT_PREVIEW"),stale=contentFixture("VALIDATE"),closed=contentFixture("VALIDATE");
        db.update("UPDATE authored_problem_draft d JOIN content_validation_snapshot s ON s.draft_id=d.id JOIN content_validation_job j ON j.snapshot_id=s.id SET d.version=2 WHERE j.id=?",stale);
        var staleState=recovery.content("VALIDATE",stale).orElseThrow();
        assertThat(recovery.draft(staleState.binding()).orElseThrow().version()).isEqualTo(2);
        code(write(root,"/operations/tasks/VALIDATE/"+stale+"/retry",retryBody(0)),409);
        code(write(root,"/operations/tasks/VALIDATE/"+valid+"/retry",retryBody(0)),200);
        var before=db.queryForMap("SELECT d.version,d.metadata_json FROM authored_problem_draft d JOIN content_validation_snapshot s ON s.draft_id=d.id JOIN content_validation_job j ON j.snapshot_id=s.id WHERE j.id=?",preview);
        code(write(root,"/operations/tasks/OUTPUT_PREVIEW/"+preview+"/retry",retryBody(0)),200);
        assertThat(db.queryForMap("SELECT d.version,d.metadata_json FROM authored_problem_draft d JOIN content_validation_snapshot s ON s.draft_id=d.id JOIN content_validation_job j ON j.snapshot_id=s.id WHERE j.id=?",preview)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM content_output_acceptance WHERE job_id=?",Integer.class,preview)).isZero();
        String review=reviewFixture(closed);
        db.update("UPDATE content_review SET review_status='WITHDRAWN',version=1,withdrawn_at=CURRENT_TIMESTAMP(6) WHERE id=?",review);
        code(write(root,"/operations/tasks/VALIDATE/"+closed+"/retry",retryBody(0)),409);
        String freshSnapshot=UUID.randomUUID().toString(),freshJob=UUID.randomUUID().toString();
        db.update("INSERT INTO content_validation_snapshot(id,draft_id,owner_id,draft_version,metadata_text,reference_code,solution_idea,solution_code,reference_sha256,solution_sha256,test_dataset_sha256,snapshot_sha256,java_image_digest,time_limit_ms,memory_limit_mb,output_limit_bytes,execution_kind) SELECT ?,s.draft_id,s.owner_id,s.draft_version,s.metadata_text,s.reference_code,s.solution_idea,s.solution_code,s.reference_sha256,s.solution_sha256,s.test_dataset_sha256,s.snapshot_sha256,s.java_image_digest,s.time_limit_ms,s.memory_limit_mb,s.output_limit_bytes,s.execution_kind FROM content_validation_snapshot s JOIN content_validation_job j ON j.snapshot_id=s.id WHERE j.id=?",freshSnapshot,closed);
        db.update("INSERT INTO content_validation_job(id,snapshot_id,owner_id,client_request_id,processing_status,attempt_count,max_attempts,delivery_sequence,last_failure_code,finished_at) VALUES(?,?,1,?,'SYSTEM_ERROR',1,1,1,'PLATFORM_FAILURE',CURRENT_TIMESTAMP(6))",freshJob,freshSnapshot,UUID.randomUUID().toString());
        db.update("INSERT INTO content_validation_attempt(id,job_id,attempt_no,lease_token,worker_id,attempt_status,lease_expires_at,finished_at,failure_code) VALUES(?,?,1,?,'fixture','DEAD_LETTERED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),'PLATFORM_FAILURE')",UUID.randomUUID().toString(),freshJob,UUID.randomUUID().toString());
        code(write(root,"/operations/tasks/VALIDATE/"+freshJob+"/retry",retryBody(0)),200);
        String latest=contentFixture("VALIDATE"),old=UUID.randomUUID().toString();String latestReview=reviewFixture(latest);
        db.update("INSERT INTO content_validation_job(id,snapshot_id,owner_id,client_request_id,processing_status,attempt_count,max_attempts,delivery_sequence,last_failure_code,finished_at,execution_kind) SELECT ?,snapshot_id,owner_id,?,'SYSTEM_ERROR',1,1,1,'PLATFORM_FAILURE',CURRENT_TIMESTAMP(6),'VALIDATE' FROM content_validation_job WHERE id=?",old,UUID.randomUUID().toString(),latest);
        db.update("UPDATE content_review SET latest_recheck_id=? WHERE id=?",latest,latestReview);
        code(write(root,"/operations/tasks/VALIDATE/"+old+"/retry",retryBody(0)),409);
        var latestBody=retryBody(0);
        code(write(root,"/operations/tasks/VALIDATE/"+latest+"/retry",latestBody),409);
        db.update("UPDATE content_validation_job SET processing_status='SYSTEM_ERROR',finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",freshJob);
        code(write(root,"/operations/tasks/VALIDATE/"+latest+"/retry",latestBody),200);
    }
    @Test void selfTestRecoveryRequiresPresentUnexpiredPayloadAndPreservesPurpose()throws Exception{
        var root=ready("root_ops");var db=db();String valid=selfFixture(),expired=selfFixture(),missing=selfFixture();
        db.update("UPDATE self_test_job SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",expired);
        db.update("DELETE p FROM self_test_payload p JOIN self_test_job j ON j.snapshot_id=p.snapshot_id WHERE j.id=?",missing);
        code(write(root,"/operations/tasks/SELF_TEST/"+expired+"/retry",retryBody(0)),409);code(write(root,"/operations/tasks/SELF_TEST/"+missing+"/retry",retryBody(0)),409);
        code(write(root,"/operations/tasks/SELF_TEST/"+valid+"/retry",retryBody(0)),200);
        assertThat(db.queryForMap("SELECT processing_status,execution_result,expires_at,max_attempts,attempt_count FROM self_test_job WHERE id=?",valid)).containsEntry("processing_status","QUEUED").containsEntry("execution_result",null).containsEntry("expires_at",null).containsEntry("max_attempts",2L).containsEntry("attempt_count",1L);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM submission WHERE client_request_id=(SELECT client_request_id FROM self_test_job WHERE id=?)",Integer.class,valid)).isZero();
    }
    @Test void naturalDeadlineKeepsOriginalAssignmentAcceptanceButRemovalArchiveAndCancellationDeny()throws Exception{
        var root=ready("root_ops");var db=db();String task=formalFixture(),room=UUID.randomUUID().toString(),assignment=UUID.randomUUID().toString();
        long problem=db.queryForObject("SELECT s.problem_id FROM submission s JOIN judge_task j ON j.submission_id=s.id WHERE j.id=?",Long.class,task);
        long judge=db.queryForObject("SELECT current_judge_version_id FROM problem WHERE id=?",Long.class,problem);
        db.update("INSERT INTO classroom(id,owner_id,title) VALUES(?,1,'recovery classroom fixture')",room);
        db.update("INSERT INTO classroom_member(classroom_id,user_id,role) VALUES(?,1,'OWNER')",room);
        db.update("UPDATE problem SET scope='CLASSROOM',classroom_id=? WHERE id=?",room,problem);
        db.update("INSERT INTO classroom_assignment(id,classroom_id,created_by,client_request_id,creation_sha256,title,description,status,deadline_at,started_at,ended_at,accept_existing_ac,allow_late,solution_policy) VALUES(?,?,1,?,REPEAT('a',64),'recovery assignment fixture','','ENDED',TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP(6)),TIMESTAMPADD(HOUR,-2,CURRENT_TIMESTAMP(6)),TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP(6)),FALSE,FALSE,'AFTER_AC')",assignment,room,UUID.randomUUID().toString());
        db.update("INSERT INTO assignment_participant(assignment_id,classroom_id,user_id) VALUES(?,?,1)",assignment,room);
        db.update("INSERT INTO assignment_problem(assignment_id,problem_id,judge_version_id,problem_slug,metadata_text,ordinal) SELECT ?,id,? ,slug,'{}',1 FROM problem WHERE id=?",assignment,judge,problem);
        db.update("INSERT INTO assignment_attempt(submission_id,assignment_id,user_id,problem_id,judge_version_id,accepted_at) SELECT s.id,?,1,s.problem_id,s.judge_version_id,TIMESTAMPADD(MINUTE,-61,CURRENT_TIMESTAMP(6)) FROM submission s JOIN judge_task j ON j.submission_id=s.id WHERE j.id=?",assignment,task);
        String path="/operations/tasks/FORMAL/"+task+"/retry";var body=retryBody(0);
        db.update("UPDATE classroom_member SET status='REMOVED' WHERE classroom_id=? AND user_id=1",room);code(write(root,path,body),409);
        db.update("UPDATE classroom_member SET status='ACTIVE' WHERE classroom_id=? AND user_id=1",room);
        db.update("UPDATE classroom SET status='ARCHIVED' WHERE id=?",room);code(write(root,path,body),409);db.update("UPDATE classroom SET status='ACTIVE' WHERE id=?",room);
        db.update("UPDATE classroom_assignment SET status='CANCELLED' WHERE id=?",assignment);code(write(root,path,body),409);db.update("UPDATE classroom_assignment SET status='ENDED' WHERE id=?",assignment);
        var before=db.queryForMap("SELECT * FROM assignment_attempt WHERE assignment_id=?",assignment);code(write(root,path,body),200);
        assertThat(db.queryForMap("SELECT * FROM assignment_attempt WHERE assignment_id=?",assignment)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT accepted_at<deadline_at FROM assignment_attempt x JOIN classroom_assignment a ON a.id=x.assignment_id WHERE x.assignment_id=?",Boolean.class,assignment)).isTrue();
    }
    @Test void publicAssignmentRecoveryStillRequiresItsCurrentRoomAndMemberForFormalAndSelf()throws Exception{
        var root=ready("root_ops");var db=db();String task=formalFixture(),room=UUID.randomUUID().toString(),assignment=UUID.randomUUID().toString();
        long problem=db.queryForObject("SELECT s.problem_id FROM submission s JOIN judge_task j ON j.submission_id=s.id WHERE j.id=?",Long.class,task);
        long judge=db.queryForObject("SELECT current_judge_version_id FROM problem WHERE id=?",Long.class,problem);
        assertThat(db.queryForMap("SELECT scope,classroom_id FROM problem WHERE id=?",problem)).containsEntry("scope","PUBLIC").containsEntry("classroom_id",null);
        db.update("INSERT INTO user_account(id,username,password_hash,status) SELECT 2,'assignment_owner_fixture',password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'");
        db.update("INSERT INTO classroom(id,owner_id,title) VALUES(?,2,'public recovery assignment fixture')",room);
        db.update("INSERT INTO classroom_member(classroom_id,user_id,role) VALUES(?,2,'OWNER'),(?,1,'MEMBER')",room,room);
        db.update("INSERT INTO classroom_assignment(id,classroom_id,created_by,client_request_id,creation_sha256,title,description,status,deadline_at,started_at,ended_at,accept_existing_ac,allow_late,solution_policy) VALUES(?,?,2,?,REPEAT('a',64),'public recovery fixture','','ENDED',TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP(6)),TIMESTAMPADD(HOUR,-2,CURRENT_TIMESTAMP(6)),TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP(6)),FALSE,FALSE,'AFTER_AC')",assignment,room,UUID.randomUUID().toString());
        db.update("INSERT INTO assignment_participant(assignment_id,classroom_id,user_id) VALUES(?,?,1)",assignment,room);
        db.update("INSERT INTO assignment_problem(assignment_id,problem_id,judge_version_id,problem_slug,metadata_text,ordinal) SELECT ?,id,?,slug,'{}',1 FROM problem WHERE id=?",assignment,judge,problem);
        db.update("INSERT INTO assignment_attempt(submission_id,assignment_id,user_id,problem_id,judge_version_id,accepted_at) SELECT s.id,?,1,s.problem_id,s.judge_version_id,TIMESTAMPADD(MINUTE,-61,CURRENT_TIMESTAMP(6)) FROM submission s JOIN judge_task j ON j.submission_id=s.id WHERE j.id=?",assignment,task);
        String self=selfFixture(problem,judge);
        db.update("INSERT INTO assignment_self_test(run_id,assignment_id,user_id,problem_id,judge_version_id) VALUES(?,?,1,?,?)",self,assignment,problem,judge);
        for(String path:List.of("/operations/tasks/FORMAL/"+task+"/retry","/operations/tasks/SELF_TEST/"+self+"/retry")){
            var body=retryBody(0);
            db.update("UPDATE classroom_member SET status='REMOVED' WHERE classroom_id=? AND user_id=1",room);code(write(root,path,body),409);
            db.update("UPDATE classroom_member SET status='ACTIVE' WHERE classroom_id=? AND user_id=1",room);
            db.update("UPDATE classroom SET status='ARCHIVED' WHERE id=?",room);code(write(root,path,body),409);db.update("UPDATE classroom SET status='ACTIVE' WHERE id=?",room);
            db.update("UPDATE classroom_assignment SET status='CANCELLED' WHERE id=?",assignment);code(write(root,path,body),409);db.update("UPDATE classroom_assignment SET status='ENDED' WHERE id=?",assignment);
            code(write(root,path,body),200);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM operations_recovery_request",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT accepted_at<deadline_at FROM assignment_attempt x JOIN classroom_assignment a ON a.id=x.assignment_id WHERE x.assignment_id=?",Boolean.class,assignment)).isTrue();
    }
    private String reviewFixture(String job){String review=UUID.randomUUID().toString();db().update("INSERT INTO content_review(id,draft_id,owner_id,draft_version,validation_job_id,snapshot_id,request_id,review_no) SELECT ?,s.draft_id,1,s.draft_version,j.id,s.id,?,1 FROM content_validation_job j JOIN content_validation_snapshot s ON s.id=j.snapshot_id WHERE j.id=?",review,UUID.randomUUID().toString(),job);return review;}
    private String contentFixture(String kind){
        String draft=UUID.randomUUID().toString(),snapshot=UUID.randomUUID().toString(),job=UUID.randomUUID().toString();var db=db();
        db.update("INSERT INTO authored_problem_draft(id,owner_id,title,metadata_json,reference_code,solution_idea,solution_code) VALUES(?,1,'recovery content fixture','{}','private reference','private idea','private solution')",draft);
        db.update("INSERT INTO content_validation_snapshot(id,draft_id,owner_id,draft_version,metadata_text,reference_code,solution_idea,solution_code,reference_sha256,solution_sha256,test_dataset_sha256,snapshot_sha256,java_image_digest,time_limit_ms,memory_limit_mb,output_limit_bytes,execution_kind) VALUES(?,?,1,1,'{}','private reference',?,?,REPEAT('a',64),REPEAT('b',64),REPEAT('c',64),REPEAT('d',64),'fixture-image',2000,256,65536,?)",snapshot,draft,kind.equals("OUTPUT_PREVIEW")?"":"private idea",kind.equals("OUTPUT_PREVIEW")?"":"private solution",kind);
        db.update("INSERT INTO content_validation_job(id,snapshot_id,owner_id,client_request_id,processing_status,attempt_count,max_attempts,delivery_sequence,last_failure_code,finished_at,execution_kind) VALUES(?,?,1,?,'SYSTEM_ERROR',1,1,1,'PLATFORM_FAILURE',CURRENT_TIMESTAMP(6),?)",job,snapshot,UUID.randomUUID().toString(),kind);
        db.update("INSERT INTO content_validation_attempt(id,job_id,attempt_no,lease_token,worker_id,attempt_status,lease_expires_at,finished_at,failure_code) VALUES(?,?,1,?,'fixture','DEAD_LETTERED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),'PLATFORM_FAILURE')",UUID.randomUUID().toString(),job,UUID.randomUUID().toString());return job;
    }
    private String selfFixture(){
        String base=formalFixture();var db=db();long problem=db.queryForObject("SELECT s.problem_id FROM submission s JOIN judge_task j ON j.submission_id=s.id WHERE j.id=?",Long.class,base);long judge=db.queryForObject("SELECT current_judge_version_id FROM problem WHERE id=?",Long.class,problem);
        return selfFixture(problem,judge);
    }
    private String selfFixture(long problem,long judge){
        var db=db();
        String snapshot=UUID.randomUUID().toString(),job=UUID.randomUUID().toString();
        db.update("INSERT INTO self_test_snapshot(id,owner_id,problem_id,problem_slug,judge_version_id,language,source_sha256,input_sha256,input_bytes,time_limit_ms,memory_limit_mb,output_limit_bytes,java_image_digest,comparison_rule_version,sandbox_policy_version,snapshot_sha256) VALUES(?,1,?,'ops self fixture',?,'JAVA_21',REPEAT('a',64),REPEAT('b',64),0,2000,256,65536,'fixture-image','trim-trailing-whitespace-v1','m0-v1',REPEAT('c',64))",snapshot,problem,judge);
        db.update("INSERT INTO self_test_payload(snapshot_id,source_code,input_text) VALUES(?,'private self source','')",snapshot);
        db.update("INSERT INTO self_test_job(id,snapshot_id,owner_id,client_request_id,processing_status,attempt_count,max_attempts,delivery_sequence,last_failure_code,finished_at,expires_at) VALUES(?,?,1,?,'SYSTEM_ERROR',1,1,1,'PLATFORM_FAILURE',CURRENT_TIMESTAMP(6),TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP(6)))",job,snapshot,UUID.randomUUID().toString());
        db.update("INSERT INTO self_test_attempt(id,job_id,attempt_no,lease_token,worker_id,attempt_status,lease_expires_at,finished_at,failure_code) VALUES(?,?,1,?,'fixture','DEAD_LETTERED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),'PLATFORM_FAILURE')",UUID.randomUUID().toString(),job,UUID.randomUUID().toString());return job;
    }
    private String formalFixture(){
        var db=db();String slug="ops-"+UUID.randomUUID(),submission=UUID.randomUUID().toString(),task=UUID.randomUUID().toString();
        db.update("INSERT INTO problem(slug,title,statement_text,input_description,output_description,public_samples_json,status) VALUES(?,'ops fixture','original fixture','input','output','[]','ACTIVE')",slug);
        long p=db.queryForObject("SELECT id FROM problem WHERE slug=?",Long.class,slug);
        db.update("INSERT INTO problem_judge_version(problem_id,version_no,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256) VALUES(?,1,2000,256,65536,'trim-trailing-whitespace-v1','m0-v1','fixture-image',REPEAT('a',64))",p);
        long j=db.queryForObject("SELECT id FROM problem_judge_version WHERE problem_id=?",Long.class,p);db.update("UPDATE problem SET current_judge_version_id=? WHERE id=?",j,p);
        db.update("INSERT INTO submission(id,user_id,problem_id,judge_version_id,client_request_id,language,source_code,source_sha256,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256,processing_status,diagnostic_message,finished_at) VALUES(?,1,?,?,?,'JAVA_21','OPS_PRIVATE_SOURCE',REPEAT('b',64),2000,256,65536,'trim-trailing-whitespace-v1','m0-v1','fixture-image',REPEAT('a',64),'SYSTEM_ERROR','OPS_PRIVATE_DIAGNOSTIC',CURRENT_TIMESTAMP(6))",submission,p,j,UUID.randomUUID().toString());
        db.update("INSERT INTO judge_task(id,submission_id,task_type,contract_version,task_status,attempt_count,max_attempts,last_failure_code,finished_at) VALUES(?,?,'JUDGE_SUBMISSION',1,'DEAD_LETTER',1,1,'PLATFORM_FAILURE',CURRENT_TIMESTAMP(6))",task,submission);
        db.update("INSERT INTO judge_task_attempt(id,judge_task_id,attempt_no,lease_token,worker_id,attempt_status,started_at,heartbeat_at,lease_expires_at,finished_at,failure_code,failure_message) VALUES(?,?,1,?,'OPS_PRIVATE_WORKER','DEAD_LETTERED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),CURRENT_TIMESTAMP(6),'PLATFORM_FAILURE','OPS_PRIVATE_ATTEMPT')",UUID.randomUUID().toString(),task,UUID.randomUUID().toString());
        db.update("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,contract_version,sequence_no,payload) VALUES(?,'JUDGE_TASK',?,'JUDGE_TASK_DEAD_LETTERED',1,1,JSON_OBJECT('taskId',?,'submissionId',?,'taskType','JUDGE_SUBMISSION','contractVersion',1))",UUID.randomUUID().toString(),task,task,submission);return task;
    }
    private JdbcTemplate db(){return restricted("forgeoj_migrator","m0-migrator-test-secret");}
    private JdbcTemplate restricted(String user,String password){return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),user,password));}
    private long create(Browser root,String name,String role)throws Exception{var r=write(root,"/accounts",Map.of("clientRequestId",UUID.randomUUID().toString(),"username",name,"role",role,"password",PASSWORD,"reason","isolated ops fixture"));code(r,201);return ((Number)read(r,"$.id")).longValue();}
    private Browser login(String name,String pass)throws Exception{var b=new Browser();code(get(b,"/auth/session"),200);code(write(b,"/auth/login",Map.of("username",name,"password",pass)),200);return b;}
    private Browser ready(String name)throws Exception{var b=login(name,PASSWORD);code(write(b,"/auth/password/change",Map.of("currentPassword",PASSWORD,"password",NEXT)),204);return login(name,NEXT);}
    private HttpResponse<String> get(Browser b,String path)throws Exception{return send(b,"GET","/api/v1/admin"+path,null);}
    private HttpResponse<String> write(Browser b,String path,Object body)throws Exception{return send(b,"POST","/api/v1/admin"+path,body);}
    private HttpResponse<String> send(Browser b,String method,String path,Object body)throws Exception{
        var q=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));if(!b.cookies.isEmpty())q.header("Cookie",b.cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("; ")));
        if(body!=null){q.header("Content-Type","application/json").header(path.startsWith("/api/v1/admin/")?"X-ADMIN-CSRF-TOKEN":"X-CSRF-TOKEN",b.csrf==null?"":b.csrf).header("Origin","http://localhost:"+port).method(method,HttpRequest.BodyPublishers.ofString(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body)));}else q.GET();
        var r=client.send(q.build(),HttpResponse.BodyHandlers.ofString());for(String v:r.headers().allValues("Set-Cookie")){var c=java.net.HttpCookie.parse(v).getFirst();if(c.getMaxAge()==0)b.cookies.remove(c.getName());else b.cookies.put(c.getName(),c.getValue());}
        if(r.statusCode()==200&&path.contains("/auth/")&&r.body().contains("\"csrf\""))b.csrf=(String)read(r,"$.csrf.token");return r;
    }
    private Object read(HttpResponse<String> r,String path){return JsonPath.read(r.body(),path);}
    private void code(HttpResponse<String> r,int expected){assertThat(r.statusCode()).describedAs("HTTP body %s",r.body()).isEqualTo(expected);}
    private static final class Browser{final Map<String,String> cookies=new HashMap<>();String csrf;}
}
