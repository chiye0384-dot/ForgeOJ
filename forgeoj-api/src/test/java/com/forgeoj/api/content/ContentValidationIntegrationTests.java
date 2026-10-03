/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

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
    "spring.flyway.locations=classpath:db/migration,classpath:db/devdata",
    "spring.rabbitmq.listener.simple.auto-startup=false","spring.rabbitmq.listener.direct.auto-startup=false"
})
class ContentValidationIntegrationTests {
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
    static final String ROOT="/api/v1/me/authored-problems";
    Browser owner,other;
    @BeforeEach void prepare() throws Exception {
        db().update("DELETE FROM outbox_event WHERE aggregate_type='CONTENT_VALIDATION'");db().update("DELETE FROM content_validation_attempt");db().update("DELETE FROM content_validation_job");db().update("DELETE FROM content_validation_test_case");db().update("DELETE FROM content_validation_snapshot");
        db().update("DELETE FROM authored_problem_test_case");db().update("DELETE FROM authored_problem_draft");
        db().update("UPDATE user_account SET status='ACTIVE' WHERE id=1");
        db().update("INSERT INTO user_account(id,username,password_hash,status) SELECT 2,'other-author',password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'");
        db().update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(2)");owner=login("learner");other=login("other-author");
    }
    @Test void validationFreezesCurrentOwnerVersionWithoutManufacturingSubmission() throws Exception {
        String id=completeDraft();int submissions=db().queryForObject("SELECT COUNT(*) FROM submission",Integer.class);
        String request=UUID.randomUUID().toString();var response=validate(owner,id,3,request);code(response,202);
        String job=JsonPath.read(response.body(),"$.jobId");assertThat(response.body()).contains("QUEUED").doesNotContain("referenceCode","solutionCode","sourceCode");
        var history=get(owner,ROOT+"/"+id+"/validations?size=1");code(history,200);
        assertThat(history.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat((Integer)JsonPath.read(history.body(),"$.total")).isEqualTo(1);
        assertThat((String)JsonPath.read(history.body(),"$.items[0].jobId")).isEqualTo(job);
        assertThat((Map<String,Object>)JsonPath.read(history.body(),"$.items[0]"))
            .containsOnlyKeys("jobId","draftId","draftVersion","processingStatus","statusVersion","validationStatus","referenceResult","solutionResult","stale");
        code(get(other,ROOT+"/"+id+"/validations"),404);code(get(owner,ROOT+"/"+id+"/validations?size=51"),400);
        assertThat((List<?>)JsonPath.read(get(owner,ROOT+"/"+id+"/validations?page=2&size=1").body(),"$.items")).isEmpty();
        assertThatThrownBy(()->db().update("UPDATE content_validation_job SET processing_status='FINISHED',reference_result='ACCEPTED',solution_result='ACCEPTED',finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",job)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db().update("UPDATE content_validation_job SET processing_status='RUNNING' WHERE id=?",job)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM submission",Integer.class)).isEqualTo(submissions);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM content_validation_snapshot WHERE draft_id=? AND draft_version=3",Integer.class,id)).isEqualTo(1);
        var current=get(owner,ROOT+"/"+id);var content=new LinkedHashMap<String,Object>(JsonPath.read(current.body(),"$.content"));content.put("referenceCode","changed after snapshot");
        code(write(owner,"PUT",ROOT+"/"+id,json(Map.of("expectedVersion",3,"content",content))),200);
        assertThat(db().queryForObject("SELECT reference_code FROM content_validation_snapshot WHERE draft_id=?",String.class,id)).contains("nextLong").doesNotContain("changed");
        code(get(owner,ROOT+"/"+id+"/validations/"+job),200);assertThat(get(owner,ROOT+"/"+id+"/validations/"+job).body()).contains("\"stale\":true");
        code(validate(owner,id,3,request),202);code(validate(owner,id,4,request),409);
        code(write(owner,"DELETE",ROOT+"/"+id+"?expectedVersion=4",null),409);
        code(write(owner,"POST",ROOT+"/"+id+"/archive","{\"expectedVersion\":4}"),200);
        code(get(owner,ROOT+"/"+id+"/validations/"+job),200);
    }
    @Test void validationNeedsCompleteExactVersionAndOwnCurrentSession() throws Exception {
        String id=create();code(validate(owner,id,1,UUID.randomUUID().toString()),400);
        id=completeDraft();code(validate(other,id,3,UUID.randomUUID().toString()),404);
        code(validate(owner,id,2,UUID.randomUUID().toString()),409);
        code(write(owner,"POST",ROOT+"/"+id+"/validations","{\"expectedVersion\":\"3\",\"requestId\":\""+UUID.randomUUID()+"\"}"),400);
        var response=validate(owner,id,3,UUID.randomUUID().toString());code(response,202);String job=JsonPath.read(response.body(),"$.jobId");
        code(get(other,ROOT+"/"+id+"/validations/"+job),404);code(get(owner,ROOT+"/"+id+"/validations/invalid"),404);
        assertThatThrownBy(()->database("forgeoj_worker","m0-worker-test-secret").queryForList("SELECT * FROM authored_problem_draft")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->database("forgeoj_api","m0-api-test-secret").update("UPDATE content_validation_snapshot SET reference_code=reference_code WHERE 1=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->database("forgeoj_worker","m0-worker-test-secret").update("UPDATE content_validation_snapshot SET solution_code=solution_code WHERE 1=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var revoked=new HashMap<>(owner.cookies);code(write(owner,"POST","/api/v1/auth/logout",null),204);owner.cookies.clear();owner.cookies.putAll(revoked);
        code(validate(owner,id,3,UUID.randomUUID().toString()),401);
    }
    @Test void requestIdIsConcurrentIdempotentAndQueueQuotaIsShared() throws Exception {
        String id=completeDraft(),request=UUID.randomUUID().toString();try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<HttpResponse<String>> action=()->{start.await();return validate(owner,id,3,request);};
            var a=pool.submit(action);var b=pool.submit(action);start.countDown();var first=a.get(30,TimeUnit.SECONDS);var second=b.get(30,TimeUnit.SECONDS);code(first,202);code(second,202);assertThat((String)JsonPath.read(first.body(),"$.jobId")).isEqualTo((String)JsonPath.read(second.body(),"$.jobId"));
        }
        code(validate(owner,id,3,UUID.randomUUID().toString()),202);code(validate(owner,id,3,UUID.randomUUID().toString()),202);code(validate(owner,id,3,UUID.randomUUID().toString()),429);

        // Formal client requires its own Idempotency-Key: test via the complete HTTP request below.
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/problems/sum-two-integers/submissions")).header("Content-Type","application/json").header("Origin","http://localhost:"+port).header("X-CSRF-TOKEN",owner.csrf).header("Cookie",cookie(owner)).header("Idempotency-Key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(json(Map.of("problemSlug","sum-two-integers","language","JAVA_21","sourceCode","public class Main {}"))));
        code(client.send(builder.build(),HttpResponse.BodyHandlers.ofString()),429);
    }
    private static String cookie(Browser b) {return b.cookies.entrySet().stream().map(e->e.getValue()).collect(java.util.stream.Collectors.joining("; "));}
    private HttpResponse<String> validate(Browser b,String id,long version,String request) throws Exception {return write(b,"POST",ROOT+"/"+id+"/validations",json(Map.of("expectedVersion",version,"requestId",request)));}
    private String completeDraft() throws Exception {
        String id=create();var content=new LinkedHashMap<String,Object>(JsonPath.read(get(owner,ROOT+"/"+id).body(),"$.content"));var metadata=new LinkedHashMap<String,Object>((Map<String,Object>)content.get("metadata"));
        metadata.put("statement","原创：求和");metadata.put("inputDescription","两个整数");metadata.put("outputDescription","和");metadata.put("licenseStatement","原创验收示例，仅用于 disposable test");metadata.put("samples",List.of(Map.of("input","1 2\n","output","3\n")));content.put("metadata",metadata);
        String code="import java.util.Scanner; public class Main { public static void main(String[] args) {Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}";content.put("referenceCode",code);content.put("solutionCode",code+"\n// independent solution object");content.put("solutionIdea","直接求和");
        code(write(owner,"PUT",ROOT+"/"+id,json(Map.of("expectedVersion",1,"content",content))),200);code(write(owner,"PUT",ROOT+"/"+id+"/tests",testsBody(2,"1 2\n","3\n")),200);return id;
    }
    private String create() throws Exception {var response=write(owner,"POST",ROOT,"{\"title\":\"原创作者草稿\"}");code(response,201);return JsonPath.read(response.body(),"$.draft.id");}
    private static String testsBody(long version,String input,String output) {return json(Map.of("expectedVersion",version,"tests",List.of(Map.of("input",input,"expectedOutput",output))));}
    private static String json(Object v) {return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(v);}
    private HttpResponse<String> sendZip(Browser b,String id,byte[] bytes,long version) throws Exception {return send(b,"PUT",ROOT+"/"+id+"/tests/zip?expectedVersion="+version,bytes,"application/zip",true);}
    private static byte[] zip(String... values) throws Exception {var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)) {for(int i=0;i<values.length;i+=2) {zip.putNextEntry(new ZipEntry(values[i]));zip.write(values[i+1].getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}}return out.toByteArray();}
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
