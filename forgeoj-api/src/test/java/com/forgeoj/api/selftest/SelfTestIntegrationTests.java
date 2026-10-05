/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.selftest;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "forgeoj.self-test.cleanup.enabled=false",
    "spring.flyway.locations=classpath:db/migration,classpath:db/devdata",
    "spring.rabbitmq.listener.simple.auto-startup=false","spring.rabbitmq.listener.direct.auto-startup=false"
})
class SelfTestIntegrationTests {
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse(
        "container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
        .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret")
        .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",()->"forgeoj_api");r.add("spring.datasource.password",()->"m0-api-test-secret");
        r.add("spring.flyway.url",MYSQL::getJdbcUrl);r.add("spring.flyway.user",()->"forgeoj_migrator");r.add("spring.flyway.password",()->"m0-migrator-test-secret");
    }
    @LocalServerPort int port;
    final HttpClient client=HttpClient.newHttpClient();
    static final String ROOT="/api/v1/self-tests";
    Browser owner,other;
    @org.springframework.beans.factory.annotation.Autowired SelfTestCleanup cleanup;
    static final String SOURCE="import java.util.Scanner; public class Main {public static void main(String[] args){Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}";
    @BeforeEach void prepare() throws Exception {
        for(String table:List.of("self_test_output","self_test_attempt","self_test_job","self_test_payload","self_test_snapshot","outbox_event","judge_task_attempt","judge_task","submission")) db().execute("DELETE FROM "+table);
        db().update("UPDATE user_account SET status='ACTIVE' WHERE id=1");
        db().update("INSERT INTO user_account(id,username,password_hash,status) SELECT 2,'other-author',password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'");
        db().update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(2)");owner=login("learner");other=login("other-author");
    }
    @Test void frozenOwnerOnlySnapshotStrictReplayAndNoFormalOrLearningWrite() throws Exception {
        String request=UUID.randomUUID().toString();var created=create(owner,request,SOURCE,"8 9\n");code(created,202);String id=id(created);
        assertThat(created.headers().firstValue("Cache-Control")).hasValue("no-store");
        code(create(owner,request,SOURCE,"8 9\n"),202);assertThat(id(create(owner,request,SOURCE,"8 9\n"))).isEqualTo(id);
        code(create(owner,request,SOURCE+"\n// changed","8 9\n"),409);code(create(owner,request,SOURCE,"1 2\n"),409);
        var detail=get(owner,ROOT+"/"+id);code(detail,200);assertThat(JsonPath.<String>read(detail.body(),"$.sourceCode")).isEqualTo(SOURCE);assertThat(JsonPath.<String>read(detail.body(),"$.input")).isEqualTo("8 9\n");
        code(get(other,ROOT+"/"+id),404);code(get(owner,ROOT+"/bad"),404);code(get(new Browser(),ROOT+"/"+id),401);
        code(write(other,"POST",ROOT+"/"+id+"/cancel",null),404);
        assertThat(JsonPath.<Number>read(get(other,"/api/v1/me/self-tests?problemSlug=sum-two-integers").body(),"$.total").intValue()).isZero();
        for(String table:List.of("submission","judge_task","user_solution_early_view","user_code_draft")) assertThat(db().queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        var events=db().queryForList("SELECT aggregate_type,CAST(payload AS CHAR) AS payload FROM outbox_event");assertThat(events).hasSize(1);assertThat(events.getFirst().get("aggregate_type")).isEqualTo("SELF_TEST");
        assertThat(((String)events.getFirst().get("payload"))).doesNotContain(SOURCE,"8 9").contains("SELF_TEST");
    }
    @Test void queuedCancellationIsIdempotentAndRunningCannotBeCancelled() throws Exception {
        String id=id(create(owner,UUID.randomUUID().toString(),SOURCE,""));code(write(owner,"POST",ROOT+"/"+id+"/cancel",null),200);code(write(owner,"POST",ROOT+"/"+id+"/cancel",null),200);
        assertThat(db().queryForObject("SELECT status_version FROM self_test_job WHERE id=?",Long.class,id)).isEqualTo(1);
        String running=id(create(owner,UUID.randomUUID().toString(),SOURCE,"1 2"));db().update("UPDATE self_test_job SET processing_status='RUNNING',lease_owner='test',lease_token=?,lease_expires_at=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=?",UUID.randomUUID().toString(),running);
        code(write(owner,"POST",ROOT+"/"+running+"/cancel",null),409);
    }
    @Test void sharedQueuedQuotaBlocksSelfTestsAndFormalCreationInBothDirections() throws Exception {
        var ids=new ArrayList<String>();for(int i=0;i<3;i++) ids.add(id(create(owner,UUID.randomUUID().toString(),SOURCE,"1 2")));
        code(create(owner,UUID.randomUUID().toString(),SOURCE,"1 2"),429);
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/problems/sum-two-integers/submissions")).header("Cookie",String.join("; ",owner.cookies.values())).header("Origin","http://localhost:"+port).header("X-CSRF-TOKEN",owner.csrf).header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(json(Map.of("language","JAVA_21","sourceCode",SOURCE)))).build();
        code(client.send(request,HttpResponse.BodyHandlers.ofString()),429);
        code(write(owner,"POST",ROOT+"/"+ids.getFirst()+"/cancel",null),200);code(create(owner,UUID.randomUUID().toString(),SOURCE,""),202);
        code(create(other,UUID.randomUUID().toString(),SOURCE,""),202);
    }
    @Test void strictBoundsPaginationAndDatabaseRolesRejectOfficialSuccessAndSnapshotMutation() throws Exception {
        String path="/api/v1/problems/sum-two-integers/self-tests";
        code(write(owner,"POST",path,json(Map.of("requestId",UUID.randomUUID().toString(),"language","JAVA_21","sourceCode",SOURCE,"input","","extra",1))),400);
        code(create(owner,UUID.randomUUID().toString(),"// public class Main {}",""),400);code(create(owner,UUID.randomUUID().toString(),SOURCE,"x".repeat(1048577)),400);code(create(owner,UUID.randomUUID().toString(),SOURCE,"\0"),400);
        code(get(owner,"/api/v1/me/self-tests?problemSlug=sum-two-integers&page=0"),400);code(get(owner,"/api/v1/me/self-tests?problemSlug=sum-two-integers&size=51"),400);
        String id=id(create(owner,UUID.randomUUID().toString(),SOURCE,""));
        for(String sql:List.of("UPDATE self_test_snapshot SET source_sha256=source_sha256 WHERE 1=0","UPDATE self_test_payload SET source_code=source_code WHERE 1=0","INSERT INTO self_test_output(job_id) VALUES('bad')","SELECT * FROM self_test_attempt LIMIT 0","DELETE FROM self_test_job WHERE 1=0")) assertThatThrownBy(()->database("forgeoj_api","m0-api-test-secret").execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        for(String sql:List.of("UPDATE self_test_snapshot SET source_sha256=source_sha256 WHERE 1=0","INSERT INTO self_test_payload(snapshot_id) VALUES('bad')","DELETE FROM self_test_output WHERE 1=0","SELECT * FROM user_code_draft LIMIT 0")) assertThatThrownBy(()->database("forgeoj_worker","m0-worker-test-secret").execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db().update("UPDATE self_test_job SET processing_status='FINISHED',execution_result='ACCEPTED',finished_at=CURRENT_TIMESTAMP(6),expires_at=TIMESTAMPADD(HOUR,24,CURRENT_TIMESTAMP(6)) WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void expiryPurgesOnlyTerminalPrivatePayloadAndRetainsIdempotencyAndClosedAttemptProof() throws Exception {
        String request=UUID.randomUUID().toString(),id=id(create(owner,request,SOURCE,"1 2")),queued=id(create(owner,UUID.randomUUID().toString(),SOURCE,"live"));
        finish(id,"3\n");code(get(owner,ROOT+"/"+id),200);
        db().update("UPDATE self_test_job SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
        code(get(owner,ROOT+"/"+id),404);code(create(owner,request,SOURCE,"1 2"),410);
        assertThat(cleanup.purge()).isEqualTo(1);assertThat(cleanup.purge()).isZero();
        assertThat(db().queryForObject("SELECT COUNT(*) FROM self_test_output",Integer.class)).isZero();
        assertThat(db().queryForObject("SELECT COUNT(*) FROM self_test_payload",Integer.class)).isEqualTo(1);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM self_test_job",Integer.class)).isEqualTo(2);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM self_test_attempt WHERE job_id=? AND attempt_status='SUCCEEDED'",Integer.class,id)).isEqualTo(1);
        code(get(owner,ROOT+"/"+queued),200);
    }
    @Test void concurrentRequestHasOneSnapshotJobAndOutboxAndCorruptOutputFailsClosed() throws Exception {
        String request=UUID.randomUUID().toString();HttpResponse<String> a,b;
        try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);Callable<HttpResponse<String>> call=()->{start.await();return create(owner,request,SOURCE,"1 2");};var x=pool.submit(call);var y=pool.submit(call);start.countDown();a=x.get(30,TimeUnit.SECONDS);b=y.get(30,TimeUnit.SECONDS);}
        code(a,202);code(b,202);assertThat(id(a)).isEqualTo(id(b));
        for(String table:List.of("self_test_snapshot","self_test_payload","self_test_job","outbox_event")) assertThat(db().queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isEqualTo(1);
        finish(id(a),"<script>inert stdout</script>\n");var detail=get(owner,ROOT+"/"+id(a));code(detail,200);assertThat(JsonPath.<String>read(detail.body(),"$.output")).isEqualTo("<script>inert stdout</script>\n");
        db().update("UPDATE self_test_output SET output_sha256=REPEAT('0',64) WHERE job_id=?",id(a));code(get(owner,ROOT+"/"+id(a)),503);
    }
    private HttpResponse<String> create(Browser b,String request,String source,String input)throws Exception{return write(b,"POST","/api/v1/problems/sum-two-integers/self-tests",json(Map.of("requestId",request,"language","JAVA_21","sourceCode",source,"input",input)));}
    private static String id(HttpResponse<String> response){code(response,202);return JsonPath.read(response.body(),"$.runId");}
    private static String json(Object value){return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value);}
    private void finish(String id,String output)throws Exception{
        // HTTP lifecycle fixture; genuine sandbox execution is separately tested in Worker/replay.
        String snapshot=db().queryForObject("SELECT snapshot_id FROM self_test_job WHERE id=?",String.class,id),token=UUID.randomUUID().toString();
        var bytes=output.getBytes(java.nio.charset.StandardCharsets.UTF_8);var compressed=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(compressed)){gzip.write(bytes);}
        db().update("INSERT INTO self_test_output(job_id,snapshot_id,output_gzip,output_bytes,output_sha256) VALUES(?,?,?,?,?)",id,snapshot,compressed.toByteArray(),bytes.length,SelfTestService.hash(output));
        db().update("INSERT INTO self_test_attempt(id,job_id,attempt_no,lease_token,worker_id,attempt_status,lease_expires_at,finished_at) VALUES(?,?,1,?,'http-test','SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",UUID.randomUUID().toString(),id,token);
        db().update("UPDATE self_test_job SET processing_status='FINISHED',execution_result='SUCCESS',finished_at=CURRENT_TIMESTAMP(6),expires_at=TIMESTAMPADD(HOUR,24,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
    }
    private Browser login(String user) throws Exception {var b=new Browser();b.csrf=JsonPath.read(get(b,"/api/v1/auth/session").body(),"$.csrf.token");var r=write(b,"POST","/api/v1/auth/login","{\"username\":\""+user+"\",\"password\":\"forgeoj-dev-only\"}");code(r,200);b.csrf=JsonPath.read(r.body(),"$.csrf.token");return b;}
    private HttpResponse<String> get(Browser b,String path) throws Exception {return send(b,"GET",path,null,"application/json",false);}
    private HttpResponse<String> write(Browser b,String method,String path,String body) throws Exception {return send(b,method,path,body==null?null:body.getBytes(java.nio.charset.StandardCharsets.UTF_8),"application/json",true);}
    private HttpResponse<String> send(Browser b,String method,String path,byte[] bytes,String type,boolean write) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(15)).header("Content-Type",type);
        if(b!=null && !b.cookies.isEmpty()) request.header("Cookie",String.join("; ",b.cookies.values()));if(write) {request.header("Origin","http://localhost:"+port);if(b!=null) request.header("X-CSRF-TOKEN",b.csrf);}
        var r=client.send(request.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString());
        if(b!=null) for(String cookie:r.headers().allValues("Set-Cookie")) {String pair=cookie.split(";",2)[0];b.cookies.put(pair.split("=",2)[0],pair);}return r;
    }
    private static void code(HttpResponse<String> r,int status) {assertThat(r.statusCode()).as(r.body()).isEqualTo(status);if(Set.of(400,401,403,404,409,503).contains(status)) assertThat(r.body()).isEmpty();}
    private JdbcTemplate db() {return database("forgeoj_migrator","m0-migrator-test-secret");}
    private JdbcTemplate database(String name,String password) {return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),name,password));}
    private static class Browser {final Map<String,String> cookies=new ConcurrentHashMap<>();String csrf;}
}
