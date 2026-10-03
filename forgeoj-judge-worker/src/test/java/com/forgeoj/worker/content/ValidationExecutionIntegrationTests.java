/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.content;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;
import com.forgeoj.worker.sandbox.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.*;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(properties={"forgeoj.worker.consumer.enabled=true","forgeoj.worker.sandbox.enabled=true","forgeoj.worker.recovery.enabled=false"})
class ValidationExecutionIntegrationTests {
    static final String IMAGE="eclipse-temurin:21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438";
    static final String SOURCE="import java.util.Scanner; public class Main { public static void main(String[] args) {Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}";
    static final String QUEUE="forgeoj.content.validation.v1",DEAD="forgeoj.content.validation.dead.v1";
    static final JsonMapper JSON=JsonMapper.builder().build();
    @Container static final MySQLContainer MYSQL=new com.forgeoj.worker.testinfra.DirectMySQLContainer(DockerImageName.parse(
        "container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
        .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret")
        .withCopyFileToContainer(MountableFile.forHostPath(repo("forgeoj-api/src/test/resources/mysql/init-test-users.sql")),"/docker-entrypoint-initdb.d/01-users.sql");
    @Container static final RabbitMQContainer RABBIT=new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.6-management@sha256:cdf40d8cb363d145e377ed88d59696a42386ffe54b30125f10eb128b862eea95").asCompatibleSubstituteFor("rabbitmq"))
        .withAdminUser("forgeoj").withAdminPassword("validation-rabbit-test-secret").withEnv("RABBITMQ_DEFAULT_VHOST","/forgeoj");
    static boolean initialized;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) throws Exception {
        initialize();p.add("spring.datasource.url",MYSQL::getJdbcUrl);p.add("spring.datasource.username",()->"forgeoj_worker");p.add("spring.datasource.password",()->"m0-worker-test-secret");
        p.add("spring.rabbitmq.host",RABBIT::getHost);p.add("spring.rabbitmq.port",RABBIT::getAmqpPort);p.add("spring.rabbitmq.username",()->"forgeoj");p.add("spring.rabbitmq.password",()->"validation-rabbit-test-secret");p.add("spring.rabbitmq.virtual-host",()->"/forgeoj");
    }
    static synchronized void initialize() throws Exception {
        if(initialized) return;
        try(var connection=DriverManager.getConnection(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret")) {
            for(String name:List.of("V1__create_m0_core_schema.sql","V2__allow_ole_verdict.sql","V3__add_m1_attempt_lease_and_retry.sql","V4__add_user_judge_quota_lock.sql","V5__widen_submission_verdict.sql")) ScriptUtils.executeSqlScript(connection,new FileSystemResource(repo("forgeoj-api/src/main/resources/db/migration/"+name)));
            com.forgeoj.worker.testinfra.ContentTestSchema.afterV5(connection,repo("forgeoj-api/src/main/resources/db/migration/V6__ordinary_accounts.sql").getParent());
            ScriptUtils.executeSqlScript(connection,new FileSystemResource(repo("forgeoj-api/src/main/resources/db/devdata/R__seed_m0_development_data.sql")));
        } initialized=true;
    }
    @Autowired ValidationLeaseService leases;
    @Autowired ValidationSnapshotLoader snapshots;
    @Autowired ValidationMapper mapper;
    @Autowired SandboxRuntime runtime;
    @Autowired SandboxAttemptLookup cleanupLookup;
    @Autowired RabbitTemplate rabbit;
    @Autowired com.forgeoj.worker.task.JudgeTaskClaimService formalClaims;
    @BeforeEach void reset() {
        new RabbitAdmin(rabbit).purgeQueue(QUEUE,true);new RabbitAdmin(rabbit).purgeQueue(DEAD,true);
        for(String table:List.of("outbox_event","judge_task_attempt","judge_task","submission","content_validation_attempt","content_validation_job","content_validation_test_case","content_validation_snapshot","authored_problem_test_case","authored_problem_draft")) db().execute("DELETE FROM "+table);
    }
    @Test void actualDurableBrokerRunsBothProgramsToPassedAndAcknowledgesAfterCommit() throws Exception {
        String job=fixture(SOURCE,SOURCE+"\n// independent object");send(job);
        awaitTerminal(job);var result=row(job);assertThat(result.get("processing_status")).isEqualTo("FINISHED");assertThat(result.get("validation_status")).isEqualTo("PASSED");
        assertThat(result.get("reference_result")).isEqualTo("ACCEPTED");assertThat(result.get("solution_result")).isEqualTo("ACCEPTED");
        assertThat(db().queryForObject("SELECT COUNT(*) FROM submission",Integer.class)).isZero();
        assertThat(db().queryForObject("SELECT attempt_status FROM content_validation_attempt WHERE job_id=?",String.class,job)).isEqualTo("SUCCEEDED");
        assertEmptyQueueAndSandboxes();
        send(job);assertEmptyQueueAndSandboxes();
        assertThat(db().queryForObject("SELECT COUNT(*) FROM content_validation_attempt WHERE job_id=?",Integer.class,job)).isEqualTo(1);
    }
    @Test void referencePassDoesNotHideIndependentWrongSolution() throws Exception {
        String job=fixture(SOURCE,"public class Main {public static void main(String[] args){System.out.println(0);}}");send(job);awaitTerminal(job);
        var result=row(job);assertThat(result.get("validation_status")).isEqualTo("FAILED");assertThat(result.get("reference_result")).isEqualTo("ACCEPTED");assertThat(result.get("solution_result")).isEqualTo("WRONG_ANSWER");assertEmptyQueueAndSandboxes();
    }
    @Test void compileErrorInPrivateReferenceStillExecutesIndependentSolution() throws Exception {
        String job=fixture("private reference compiler sentinel invalid",SOURCE);send(job);awaitTerminal(job);
        var result=row(job);assertThat(result.get("validation_status")).isEqualTo("FAILED");assertThat(result.get("reference_result")).isEqualTo("COMPILE_ERROR");assertThat(result.get("solution_result")).isEqualTo("ACCEPTED");assertEmptyQueueAndSandboxes();
    }
    @Test void claimIsSingleWinnerAndExpiredOwnershipCannotRenewFinishOrFailAfterRecovery() throws Exception {
        String job=fixture(SOURCE,SOURCE),snapshot=(String)row(job).get("snapshot_id");ValidationLeaseService.Claim old;
        try(var pool=Executors.newFixedThreadPool(2)) {var start=new CountDownLatch(1);Callable<ValidationLeaseService.Claim> action=()->{start.await();return leases.claim(job,snapshot);};var a=pool.submit(action);var b=pool.submit(action);start.countDown();var claims=Arrays.asList(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));assertThat(claims.stream().filter(Objects::nonNull).count()).isEqualTo(1);old=claims.stream().filter(Objects::nonNull).findFirst().orElseThrow();}
        assertThat(snapshots.load(old).reference().sourceCode()).isEqualTo(SOURCE);
        var orphan=runtime.prepare(snapshots.load(old).reference(),old.attemptId());
        db().update("UPDATE content_validation_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",job);
        db().update("UPDATE content_validation_attempt SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",old.attemptId());
        assertThatThrownBy(()->leases.renew(old)).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->leases.finish(old,"ACCEPTED","ACCEPTED")).isInstanceOf(IllegalStateException.class);
        leases.recover(job);assertThat(cleanupLookup.isClosed(job,old.attemptId())).isTrue();runtime.cleanupManagedContainers();
        assertThat(db().queryForObject("SELECT attempt_status FROM content_validation_attempt WHERE id=?",String.class,old.attemptId())).isEqualTo("LEASE_EXPIRED");
        db().update("UPDATE content_validation_job SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=?",job);var current=leases.claim(job,snapshot);assertThat(current.token()).isNotEqualTo(old.token());
        assertThatThrownBy(()->leases.renew(old)).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->leases.finish(old,"ACCEPTED","ACCEPTED")).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->leases.failure(old,false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->snapshots.load(old)).isInstanceOf(com.forgeoj.worker.snapshot.JudgeTaskSnapshotException.class);
        leases.finish(current,"ACCEPTED","ACCEPTED");assertThat(cleanupLookup.isClosed(job,current.attemptId())).isTrue();
        assertThat(row(job).get("validation_status")).isEqualTo("PASSED");assertNoSandbox();
    }
    @Test void platformRetriesRetainRunningQuotaAndExhaustToErrorWithoutValidationSuccess() {
        String job=fixture(SOURCE,SOURCE),snapshot=(String)row(job).get("snapshot_id");
        for(int attempt=1;attempt<=3;attempt++) {var claim=leases.claim(job,snapshot);assertThat(claim).isNotNull();leases.failure(claim,false);
            if(attempt<3) {assertThat(row(job).get("processing_status")).isEqualTo("WAITING_RETRY");assertThat(mapper.otherRunning(1,UUID.randomUUID().toString())).isEqualTo(1);db().update("UPDATE content_validation_job SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=?",job);}
        }
        var result=row(job);assertThat(result.get("processing_status")).isEqualTo("SYSTEM_ERROR");assertThat(result.get("validation_status")).isNull();assertThat(result.get("reference_result")).isNull();assertThat(result.get("solution_result")).isNull();assertThat(result.get("lease_token")).isNull();
        assertThat(db().queryForObject("SELECT COUNT(*) FROM content_validation_attempt WHERE job_id=?",Integer.class,job)).isEqualTo(3);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='CONTENT_VALIDATION_DEAD_LETTERED'",Integer.class,job)).isEqualTo(1);
        assertThat(leases.claim(job,snapshot)).isNull();
    }
    @Test void tamperedFrozenInputsFailClosedAndRuntimeRoleCannotReadMutableAuthoring() {
        String job=fixture(SOURCE,SOURCE),snapshot=(String)row(job).get("snapshot_id");var claim=leases.claim(job,snapshot);
        db().update("UPDATE content_validation_snapshot SET solution_code='changed by disposable migrator' WHERE id=?",snapshot);
        assertThatThrownBy(()->snapshots.load(claim)).isInstanceOf(com.forgeoj.worker.snapshot.JudgeTaskSnapshotException.class);leases.failure(claim,true);assertThat(row(job).get("processing_status")).isEqualTo("SYSTEM_ERROR");
        for(String table:List.of("authored_problem_draft","authored_problem_test_case","user_account","user_code_draft")) assertThatThrownBy(()->restricted().queryForList("SELECT * FROM "+table+" LIMIT 0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->restricted().update("UPDATE content_validation_snapshot SET reference_code=reference_code WHERE 1=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void oneRunningJobDefersOtherJobWithoutConsumingAttempt() {
        String first=fixture(SOURCE,SOURCE),second=fixture(SOURCE,SOURCE);var running=leases.claim(first,(String)row(first).get("snapshot_id"));
        assertThat(leases.claim(second,(String)row(second).get("snapshot_id"))).isNull();assertThat(((Number)row(second).get("attempt_count")).intValue()).isZero();assertThat(row(second).get("processing_status")).isEqualTo("QUEUED");
        leases.finish(running,"ACCEPTED","ACCEPTED");db().update("UPDATE content_validation_job SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=?",second);
        var next=leases.claim(second,(String)row(second).get("snapshot_id"));assertThat(next).isNotNull();leases.finish(next,"ACCEPTED","ACCEPTED");
    }
    @Test void malformedMessagesIncludingDuplicateKeysGoOnlyToContentDeadQueue() throws Exception {
        String id=UUID.randomUUID().toString(),snapshot=UUID.randomUUID().toString();
        String body=JSON.writeValueAsString(Map.of("taskId",id,"snapshotId",snapshot,"taskType","CONTENT_VALIDATE","contractVersion",1));
        for(String invalid:List.of(body.replace("\"contractVersion\":1","\"contractVersion\":1.0"),
                body.substring(0,body.length()-1)+",\"taskId\":\""+id+"\"}",
                body.substring(0,body.length()-1)+",\"sourceCode\":\"private transport sentinel\"}")) {
            rabbit.send("forgeoj.judge","content.validation.v1",new Message(invalid.getBytes(StandardCharsets.UTF_8)));
            assertThat(rabbit.receive(DEAD,10000)).isNotNull();
        }
        assertThat(db().queryForObject("SELECT COUNT(*) FROM content_validation_attempt",Integer.class)).isZero();
        assertEmptyQueueAndSandboxes();
    }
    @Test void formalAndContentTasksShareTheSameRunningSlotInBothDirections() {
        String job=fixture(SOURCE,SOURCE),snapshot=(String)row(job).get("snapshot_id");
        var validation=leases.claim(job,snapshot);String submission=UUID.randomUUID().toString(),task=UUID.randomUUID().toString();
        db().update("""
            INSERT INTO submission(id,user_id,problem_id,judge_version_id,client_request_id,language,source_code,source_sha256,
                time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256,processing_status)
            VALUES(?,1,1,1,?,'JAVA_21','public class Main {}',REPEAT('a',64),2000,256,1048576,
                'trim-trailing-whitespace-v1','m0-v1','eclipse-temurin:test',REPEAT('b',64),'QUEUED')
            """,submission,UUID.randomUUID().toString());
        db().update("INSERT INTO judge_task(id,submission_id,task_type,contract_version,task_status) VALUES(?,?,'JUDGE_SUBMISSION',1,'QUEUED')",task,submission);
        var message=new com.forgeoj.worker.messaging.JudgeTaskMessage(task,submission,"JUDGE_SUBMISSION",1);
        assertThat(formalClaims.claim(message).outcome()).isEqualTo(com.forgeoj.worker.task.TaskClaimOutcome.DEFERRED);
        assertThat(db().queryForObject("SELECT attempt_count FROM judge_task WHERE id=?",Integer.class,task)).isZero();
        leases.finish(validation,"ACCEPTED","ACCEPTED");
        db().update("UPDATE judge_task SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=?",task);
        assertThat(formalClaims.claim(message).outcome()).isEqualTo(com.forgeoj.worker.task.TaskClaimOutcome.CLAIMED);
        String next=fixture(SOURCE,SOURCE);
        assertThat(leases.claim(next,(String)row(next).get("snapshot_id"))).isNull();
        assertThat(((Number)row(next).get("attempt_count")).intValue()).isZero();
        assertThat(row(next).get("processing_status")).isEqualTo("QUEUED");
    }
    private String fixture(String reference,String solution) {
        String draft=UUID.randomUUID().toString(),snapshot=UUID.randomUUID().toString(),job=UUID.randomUUID().toString();
        String metadata=JSON.writeValueAsString(Map.of("timeLimitMs",2000,"memoryLimitMb",256,"outputLimitBytes",1048576)),idea="original disposable solution";
        String refHash=ValidationSnapshotLoader.hash(reference),solHash=ValidationSnapshotLoader.hash(solution),in="1 2\n",out="3\n",inHash=ValidationSnapshotLoader.hash(in),outHash=ValidationSnapshotLoader.hash(out);
        String dataset=ValidationSnapshotLoader.hash("1:"+inHash+":"+outHash+"\n");String identity=ValidationSnapshotLoader.hash(ValidationSnapshotLoader.framed(draft,"1","1",metadata,idea,refHash,solHash,dataset,IMAGE,"trim-trailing-whitespace-v1","m0-v1"));
        db().update("INSERT INTO authored_problem_draft(id,owner_id,title,metadata_json,reference_code,solution_idea,solution_code) VALUES(?,1,'original fixture',JSON_OBJECT(),'','','')",draft);
        db().update("INSERT INTO content_validation_snapshot(id,draft_id,owner_id,draft_version,metadata_text,reference_code,solution_idea,solution_code,reference_sha256,solution_sha256,test_dataset_sha256,snapshot_sha256,java_image_digest,time_limit_ms,memory_limit_mb,output_limit_bytes) VALUES(?,?,1,1,?,?,?,?,?,?,?,?,?,2000,256,1048576)",snapshot,draft,metadata,reference,idea,solution,refHash,solHash,dataset,identity,IMAGE);
        db().update("INSERT INTO content_validation_test_case(snapshot_id,sequence_no,input_gzip,expected_output_gzip,input_bytes,expected_output_bytes,input_sha256,expected_output_sha256) VALUES(?,1,?,?,4,2,?,?)",snapshot,gzip(in),gzip(out),inHash,outHash);
        db().update("INSERT INTO content_validation_job(id,snapshot_id,owner_id,client_request_id) VALUES(?,?,1,?)",job,snapshot,UUID.randomUUID().toString());return job;
    }
    private void send(String job) {rabbit.send("forgeoj.judge","content.validation.v1",new Message(JSON.writeValueAsBytes(Map.of("taskId",job,"snapshotId",row(job).get("snapshot_id"),"taskType","CONTENT_VALIDATE","contractVersion",1))));}
    private void awaitTerminal(String job) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);while(System.nanoTime()<end) {if(Set.of("FINISHED","SYSTEM_ERROR").contains(row(job).get("processing_status"))) return;Thread.sleep(100);}fail("Real validation did not become terminal");}
    private void assertEmptyQueueAndSandboxes() throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);String latest="";
        while(System.nanoTime()<end) {
            var result=RABBIT.execInContainer("rabbitmqctl","--quiet","list_queues","-p","/forgeoj","name","messages_ready","messages_unacknowledged","--formatter","json");
            assertThat(result.getExitCode()).isZero();latest=result.getStdout();
            for(Object value:JSON.readValue(latest,List.class)) {
                if(value instanceof Map<?,?> queue && QUEUE.equals(queue.get("name"))
                    && queue.get("messages_ready") instanceof Number ready && ready.longValue()==0
                    && queue.get("messages_unacknowledged") instanceof Number unacked && unacked.longValue()==0) {
                    assertNoSandbox();return;
                }
            }
            Thread.sleep(100);
        }
        fail("Validation ACK/queue did not settle: "+latest);
    }
    private void assertNoSandbox() throws Exception {var process=new ProcessBuilder("docker","ps","-aq","--filter","label=com.forgeoj.managed=true").redirectErrorStream(true).start();assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertThat(process.exitValue()).as(output).isZero();assertThat(output).isBlank();}
    private Map<String,Object> row(String id) {return db().queryForMap("SELECT * FROM content_validation_job WHERE id=?",id);}
    private JdbcTemplate db() {return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret"));}
    private JdbcTemplate restricted() {return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_worker","m0-worker-test-secret"));}
    private static byte[] gzip(String text) {try {var out=new ByteArrayOutputStream();try(var stream=new GZIPOutputStream(out)){stream.write(text.getBytes(StandardCharsets.UTF_8));}return out.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}}
    private static Path repo(String relative) {Path local=Path.of(relative);return Files.exists(local)?local:Path.of("..").resolve(relative);}
}
