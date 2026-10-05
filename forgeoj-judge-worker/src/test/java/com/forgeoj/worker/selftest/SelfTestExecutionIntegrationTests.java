/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.selftest;

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
class SelfTestExecutionIntegrationTests {
    static final String IMAGE="eclipse-temurin:21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438";
    static final String SOURCE="import java.util.Scanner; public class Main { public static void main(String[] args) {Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}";
    static final String QUEUE="forgeoj.judge.self-test.v1",DEAD="forgeoj.judge.self-test.dead.v1";
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
    @Autowired SelfTestLeaseService leases;
    @Autowired SelfTestSnapshotLoader snapshots;
    @Autowired SelfTestMapper mapper;
    @Autowired SandboxRuntime runtime;
    @Autowired SandboxAttemptLookup cleanupLookup;
    @Autowired RabbitTemplate rabbit;
    @Autowired com.forgeoj.worker.task.JudgeTaskClaimService formalClaims;
    @Autowired com.forgeoj.worker.content.ValidationMapper content;
    @BeforeEach void reset(){
        new RabbitAdmin(rabbit).purgeQueue(QUEUE,true);new RabbitAdmin(rabbit).purgeQueue(DEAD,true);
        for(String table:List.of("self_test_output","self_test_attempt","self_test_job","self_test_payload","self_test_snapshot","outbox_event","judge_task_attempt","judge_task","submission")) db().execute("DELETE FROM "+table);
    }
    @Test void actualBrokerCustomInputProducesSuccessOutputWithoutFormalAcAndDuplicateExecutesOnce() throws Exception {
        String job=fixture(SOURCE,"80 9\n",1048576);send(job);awaitTerminal(job);
        assertThat(row(job).get("processing_status")).isEqualTo("FINISHED");assertThat(row(job).get("execution_result")).isEqualTo("SUCCESS");
        var output=db().queryForMap("SELECT * FROM self_test_output WHERE job_id=?",job);
        try(var stream=new java.util.zip.GZIPInputStream(new ByteArrayInputStream((byte[])output.get("output_gzip")))){assertThat(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).isEqualTo("89\n");}
        assertThat(output.get("output_sha256")).isEqualTo(SelfTestSnapshotLoader.hash("89\n"));
        assertThat(db().queryForObject("SELECT COUNT(*) FROM submission",Integer.class)).isZero();
        assertThat(cleanupLookup.isClosed(job,db().queryForObject("SELECT id FROM self_test_attempt WHERE job_id=?",String.class,job))).isTrue();
        assertEmptyQueueAndSandboxes();send(job);assertEmptyQueueAndSandboxes();
        assertThat(db().queryForObject("SELECT COUNT(*) FROM self_test_attempt",Integer.class)).isEqualTo(1);
    }
    @Test void actualFailuresHaveNoOutputOrRetriesAndEmptyStdoutIsSuccessful() throws Exception {
        var programs=List.of("public class Main { broken }","public class Main {public static void main(String[] a){throw new RuntimeException();}}","public class Main {public static void main(String[] a){System.out.write(195);System.out.write(40);System.out.flush();}}","public class Main {public static void main(String[] a){System.out.print(\"x\".repeat(1048577));}}","public class Main {public static void main(String[] a){while(true){}}}");
        var outcomes=List.of("COMPILE_ERROR","RUNTIME_ERROR","RUNTIME_ERROR","OUTPUT_LIMIT_EXCEEDED","TIME_LIMIT_EXCEEDED");
        for(int i=0;i<programs.size();i++){String job=fixture(programs.get(i),"",1048576);send(job);awaitTerminal(job);assertThat(row(job).get("execution_result")).isEqualTo(outcomes.get(i));assertThat(db().queryForObject("SELECT COUNT(*) FROM self_test_output WHERE job_id=?",Integer.class,job)).isZero();assertThat(((Number)row(job).get("attempt_count")).intValue()).isEqualTo(1);assertEmptyQueueAndSandboxes();}
        String empty=fixture("public class Main {public static void main(String[] a){}}","",1048576);send(empty);awaitTerminal(empty);assertThat(row(empty).get("execution_result")).isEqualTo("SUCCESS");assertThat(db().queryForObject("SELECT output_bytes FROM self_test_output WHERE job_id=?",Long.class,empty)).isZero();assertEmptyQueueAndSandboxes();
    }
    @Test void oldLeaseCannotLoadRenewOrWriteOutputAndClosedProofAuthorizesOnlyOwnedOrphan() throws Exception {
        String job=fixture(SOURCE,"1 2",1048576),snapshot=(String)row(job).get("snapshot_id");var old=leases.claim(job,snapshot);
        runtime.prepare(snapshots.load(old),old.attemptId());assertThat(cleanupLookup.isClosed(job,old.attemptId())).isFalse();
        db().update("UPDATE self_test_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",job);leases.recover(job);
        assertThat(cleanupLookup.isClosed(job,old.attemptId())).isTrue();runtime.cleanupManagedContainers();
        assertThatThrownBy(()->leases.finish(old,new SandboxOutputPreview(SandboxOutcome.ACCEPTED,List.of("3\n")))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->leases.renew(old)).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->snapshots.load(old)).isInstanceOf(com.forgeoj.worker.snapshot.JudgeTaskSnapshotException.class);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM self_test_output",Integer.class)).isZero();
        db().update("UPDATE self_test_job SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=?",job);var fresh=leases.claim(job,snapshot);assertThat(fresh.token()).isNotEqualTo(old.token());leases.finish(fresh,runtime.generate(snapshots.load(fresh),fresh.attemptId()));assertThat(row(job).get("execution_result")).isEqualTo("SUCCESS");assertNoSandbox();
    }
    @Test void singleWinnerQuotaDeferralAndPlatformExhaustionKeepOneRunningReservation() throws Exception {
        String job=fixture(SOURCE,"1 2",1048576),snapshot=(String)row(job).get("snapshot_id");SelfTestLeaseService.Claim first;
        try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);Callable<SelfTestLeaseService.Claim> action=()->{start.await();return leases.claim(job,snapshot);};var a=pool.submit(action);var b=pool.submit(action);start.countDown();var claims=Arrays.asList(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));assertThat(claims.stream().filter(Objects::nonNull).count()).isEqualTo(1);first=claims.stream().filter(Objects::nonNull).findFirst().orElseThrow();}
        String next=fixture(SOURCE,"1 2",1048576);assertThat(leases.claim(next,(String)row(next).get("snapshot_id"))).isNull();assertThat(row(next).get("processing_status")).isEqualTo("QUEUED");assertThat(((Number)row(next).get("attempt_count")).intValue()).isZero();
        leases.failure(first,false);assertThat(row(job).get("processing_status")).isEqualTo("WAITING_RETRY");assertThat(content.otherRunning(1,UUID.randomUUID().toString())).isEqualTo(1);
        for(int i=2;i<=3;i++){db().update("UPDATE self_test_job SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=?",job);leases.failure(leases.claim(job,snapshot),false);}
        assertThat(row(job).get("processing_status")).isEqualTo("SYSTEM_ERROR");assertThat(row(job).get("execution_result")).isNull();assertThat(row(job).get("expires_at")).isNotNull();assertThat(db().queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='SELF_TEST_DEAD_LETTERED'",Integer.class,job)).isEqualTo(1);
        assertThat(db().queryForList("SELECT CAST(payload AS CHAR) AS payload FROM outbox_event").toString()).doesNotContain(SOURCE,"sourceCode","inputText");
    }
    @Test void immutableTamperFailsClosedAndCancelledDeliveryHasNoAttempt() throws Exception {
        String job=fixture(SOURCE,"1 2",1048576),snapshot=(String)row(job).get("snapshot_id");var claim=leases.claim(job,snapshot);
        db().update("UPDATE self_test_payload SET input_text='changed' WHERE snapshot_id=?",snapshot);
        assertThatThrownBy(()->snapshots.load(claim)).isInstanceOf(com.forgeoj.worker.snapshot.JudgeTaskSnapshotException.class);leases.failure(claim,true);assertThat(row(job).get("processing_status")).isEqualTo("SYSTEM_ERROR");
        String cancelled=fixture(SOURCE,"",1048576);db().update("UPDATE self_test_job SET processing_status='CANCELLED',finished_at=CURRENT_TIMESTAMP(6),expires_at=TIMESTAMPADD(HOUR,24,CURRENT_TIMESTAMP(6)) WHERE id=?",cancelled);send(cancelled);assertEmptyQueueAndSandboxes();assertThat(db().queryForObject("SELECT COUNT(*) FROM self_test_attempt WHERE job_id=?",Integer.class,cancelled)).isZero();
        for(String sql:List.of("SELECT * FROM authored_problem_draft LIMIT 0","SELECT * FROM user_account LIMIT 0","UPDATE self_test_payload SET input_text=input_text WHERE 1=0","UPDATE self_test_job SET snapshot_id=snapshot_id WHERE 1=0","DELETE FROM self_test_attempt WHERE 1=0")) assertThatThrownBy(()->restricted().execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void strictMessagesAreRejectedWithoutRequeueOrExecution() throws Exception {
        String id=UUID.randomUUID().toString(),snapshot=UUID.randomUUID().toString(),body=JSON.writeValueAsString(Map.of("taskId",id,"snapshotId",snapshot,"taskType","SELF_TEST","contractVersion",1));
        for(String invalid:List.of(body,body.replace("\"contractVersion\":1","\"contractVersion\":1.0"),body.substring(0,body.length()-1)+",\"taskId\":\""+id+"\"}",body.substring(0,body.length()-1)+",\"sourceCode\":\"private\"}","x".repeat(1025))){rabbit.send("forgeoj.judge","judge.self-test.v1",new Message(invalid.getBytes(StandardCharsets.UTF_8)));assertEmptyQueueAndSandboxes();}
        assertThat(db().queryForObject("SELECT COUNT(*) FROM self_test_attempt",Integer.class)).isZero();
    }
    @Test void selfTestAndFormalTasksShareRunningQuotaInBothDirections() {
        String job=fixture(SOURCE,"1 2",1048576);var run=leases.claim(job,(String)row(job).get("snapshot_id"));String submission=UUID.randomUUID().toString(),task=UUID.randomUUID().toString();
        db().update("INSERT INTO submission(id,user_id,problem_id,judge_version_id,client_request_id,language,source_code,source_sha256,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256,processing_status) VALUES(?,1,1,1,?,'JAVA_21','public class Main {}',REPEAT('a',64),2000,256,1048576,'trim-trailing-whitespace-v1','m0-v1','eclipse-temurin:test',REPEAT('b',64),'QUEUED')",submission,UUID.randomUUID().toString());
        db().update("INSERT INTO judge_task(id,submission_id,task_type,contract_version,task_status) VALUES(?,?,'JUDGE_SUBMISSION',1,'QUEUED')",task,submission);
        var message=new com.forgeoj.worker.messaging.JudgeTaskMessage(task,submission,"JUDGE_SUBMISSION",1);assertThat(formalClaims.claim(message).outcome()).isEqualTo(com.forgeoj.worker.task.TaskClaimOutcome.DEFERRED);
        leases.finish(run,new SandboxOutputPreview(SandboxOutcome.ACCEPTED,List.of("3\n")));db().update("UPDATE judge_task SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=?",task);assertThat(formalClaims.claim(message).outcome()).isEqualTo(com.forgeoj.worker.task.TaskClaimOutcome.CLAIMED);
        String next=fixture(SOURCE,"1 2",1048576);assertThat(leases.claim(next,(String)row(next).get("snapshot_id"))).isNull();assertThat(((Number)row(next).get("attempt_count")).intValue()).isZero();
    }
    @Test void invalidOutputRollsBackTerminalAndOutputAndCannotInventWrongAnswer() {
        String job=fixture(SOURCE,"1 2",1048576);var claim=leases.claim(job,(String)row(job).get("snapshot_id"));
        assertThatThrownBy(()->leases.finish(claim,new SandboxOutputPreview(SandboxOutcome.ACCEPTED,List.of("x".repeat(1048577))))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->leases.finish(claim,new SandboxOutputPreview(SandboxOutcome.WRONG_ANSWER,List.of()))).isInstanceOf(IllegalStateException.class);
        assertThat(row(job).get("processing_status")).isEqualTo("RUNNING");assertThat(db().queryForObject("SELECT COUNT(*) FROM self_test_output",Integer.class)).isZero();
    }
    private String fixture(String code,String input,long limit){
        String snapshot=UUID.randomUUID().toString(),job=UUID.randomUUID().toString(),sourceHash=SelfTestSnapshotLoader.hash(code),inputHash=SelfTestSnapshotLoader.hash(input);long bytes=input.getBytes(StandardCharsets.UTF_8).length;
        String identity=SelfTestSnapshotLoader.hash(SelfTestSnapshotLoader.framed("1","1","sum-two-integers","1","JAVA_21",sourceHash,inputHash,Long.toString(bytes),"2000","256",Long.toString(limit),IMAGE,"trim-trailing-whitespace-v1","m0-v1"));
        db().update("INSERT INTO self_test_snapshot(id,owner_id,problem_id,problem_slug,judge_version_id,language,source_sha256,input_sha256,input_bytes,time_limit_ms,memory_limit_mb,output_limit_bytes,java_image_digest,comparison_rule_version,sandbox_policy_version,snapshot_sha256) VALUES(?,1,1,'sum-two-integers',1,'JAVA_21',?,?,?,2000,256,?,?,'trim-trailing-whitespace-v1','m0-v1',?)",snapshot,sourceHash,inputHash,bytes,limit,IMAGE,identity);
        db().update("INSERT INTO self_test_payload(snapshot_id,source_code,input_text) VALUES(?,?,?)",snapshot,code,input);db().update("INSERT INTO self_test_job(id,snapshot_id,owner_id,client_request_id) VALUES(?,?,1,?)",job,snapshot,UUID.randomUUID().toString());return job;
    }
    private void send(String job){rabbit.send("forgeoj.judge","judge.self-test.v1",new Message(JSON.writeValueAsBytes(Map.of("taskId",job,"snapshotId",row(job).get("snapshot_id"),"taskType","SELF_TEST","contractVersion",1))));}
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
    private Map<String,Object> row(String id) {return db().queryForMap("SELECT * FROM self_test_job WHERE id=?",id);}
    private JdbcTemplate db() {return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret"));}
    private JdbcTemplate restricted() {return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_worker","m0-worker-test-secret"));}
    private static byte[] gzip(String text) {try {var out=new ByteArrayOutputStream();try(var stream=new GZIPOutputStream(out)){stream.write(text.getBytes(StandardCharsets.UTF_8));}return out.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}}
    private static Path repo(String relative) {Path local=Path.of(relative);return Files.exists(local)?local:Path.of("..").resolve(relative);}
}
