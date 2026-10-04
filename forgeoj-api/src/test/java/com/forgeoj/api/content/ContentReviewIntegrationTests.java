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
class ContentReviewIntegrationTests {
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
        db().update("DELETE FROM content_review");db().update("DELETE FROM outbox_event WHERE aggregate_type='CONTENT_VALIDATION'");db().update("DELETE FROM content_validation_attempt");db().update("DELETE FROM content_validation_job");db().update("DELETE FROM content_validation_test_case");db().update("DELETE FROM content_validation_snapshot");
        db().update("DELETE FROM authored_problem_test_case");db().update("DELETE FROM authored_problem_draft");
        db().update("UPDATE user_account SET status='ACTIVE' WHERE id=1");
        db().update("INSERT INTO user_account(id,username,password_hash,status) SELECT 2,'other-author',password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'");
        db().update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(2)");owner=login("learner");other=login("other-author");
    }

    @Test void pendingReviewLocksEveryAuthorWriteAndWithdrawKeepsFrozenHistory() throws Exception {
        String draft=completeDraft(),job=passed(draft),request=UUID.randomUUID().toString();
        int submissions=db().queryForObject("SELECT COUNT(*) FROM submission",Integer.class),problems=db().queryForObject("SELECT COUNT(*) FROM problem",Integer.class);
        var submitted=review(owner,draft,3,job,request);code(submitted,202);String review=JsonPath.read(submitted.body(),"$.reviewId");
        assertThat((Map<String,Object>)JsonPath.read(submitted.body(),"$")).containsOnlyKeys("reviewId","draftId","draftVersion","reviewNo","validationJobId","status","version");
        assertThat(get(owner,ROOT+"/"+draft).body()).contains("UNDER_REVIEW");
        assertThat(get(owner,ROOT+"?page=1&size=20").body()).contains("UNDER_REVIEW");
        var content=(Map<String,Object>)JsonPath.read(get(owner,ROOT+"/"+draft).body(),"$.content");
        code(write(owner,"PUT",ROOT+"/"+draft,json(Map.of("expectedVersion",3,"content",content))),409);
        code(write(owner,"PUT",ROOT+"/"+draft+"/tests",testsBody(3,"9 9","18")),409);
        code(sendZip(owner,draft,zip("001.in","9 9","001.out","18"),3),409);
        code(write(owner,"POST",ROOT+"/"+draft+"/archive","{\"expectedVersion\":3}"),409);
        code(write(owner,"DELETE",ROOT+"/"+draft+"?expectedVersion=3",null),409);
        code(validate(owner,draft,3,UUID.randomUUID().toString()),409);
        code(review(owner,draft,3,job,UUID.randomUUID().toString()),409);
        code(review(owner,draft,3,job,request),202);
        String detailPath=ROOT+"/"+draft+"/reviews/"+review;
        var frozen=get(owner,detailPath);code(frozen,200);assertThat(frozen.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat((Map<String,Object>)JsonPath.read(frozen.body(),"$")).containsOnlyKeys("review","content","testCount");
        assertThat((Integer)JsonPath.read(frozen.body(),"$.testCount")).isEqualTo(1);assertThat(frozen.body()).doesNotContain("inputGzip","inputSha256","snapshotId","requestId");
        code(withdraw(owner,draft,review,3,1),409);code(withdraw(owner,draft,review,2,0),409);
        code(withdraw(owner,draft,review,3,0),200);code(withdraw(owner,draft,review,3,0),200);
        assertThat(get(owner,ROOT+"/"+draft).body()).contains("\"status\":\"DRAFT\"");
        var changed=new LinkedHashMap<String,Object>(content);changed.put("solutionIdea","edited after withdrawal");
        code(write(owner,"PUT",ROOT+"/"+draft,json(Map.of("expectedVersion",3,"content",changed))),200);
        assertThat((Map<String,Object>)JsonPath.read(get(owner,detailPath).body(),"$.content")).isEqualTo((Map<String,Object>)JsonPath.read(frozen.body(),"$.content"));
        code(review(owner,draft,4,job,UUID.randomUUID().toString()),409);
        assertThat((String)JsonPath.read(review(owner,draft,3,job,request).body(),"$.status")).isEqualTo("WITHDRAWN");
        code(write(owner,"DELETE",ROOT+"/"+draft+"?expectedVersion=4",null),409);
        code(write(owner,"POST",ROOT+"/"+draft+"/archive","{\"expectedVersion\":4}"),200);code(get(owner,detailPath),200);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM submission",Integer.class)).isEqualTo(submissions);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM problem",Integer.class)).isEqualTo(problems);
    }
    @Test void ownerPassedVersionStrictBodyAndCurrentSessionAreMandatory() throws Exception {
        String draft=completeDraft(),job=JsonPath.read(validate(owner,draft,3,UUID.randomUUID().toString()).body(),"$.jobId");
        code(review(owner,draft,3,job,UUID.randomUUID().toString()),409);
        db().update("UPDATE content_validation_job SET processing_status='FINISHED',validation_status='FAILED',reference_result='ACCEPTED',solution_result='WRONG_ANSWER',finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",job);
        code(review(owner,draft,3,job,UUID.randomUUID().toString()),409);
        db().update("UPDATE content_validation_job SET validation_status='PASSED',solution_result='ACCEPTED' WHERE id=?",job);
        code(review(other,draft,3,job,UUID.randomUUID().toString()),404);code(review(owner,draft,2,job,UUID.randomUUID().toString()),409);
        code(write(owner,"POST",ROOT+"/"+draft+"/reviews",json(Map.of("expectedVersion",3.0,"validationJobId",job,"requestId",UUID.randomUUID().toString()))),400);
        code(write(owner,"POST",ROOT+"/"+draft+"/reviews",json(Map.of("expectedVersion",3,"validationJobId",job,"requestId",UUID.randomUUID().toString(),"approve",true))),400);
        String review=JsonPath.read(review(owner,draft,3,job,UUID.randomUUID().toString()).body(),"$.reviewId");
        code(get(other,ROOT+"/"+draft+"/reviews"),404);code(get(other,ROOT+"/"+draft+"/reviews/"+review),404);code(withdraw(other,draft,review,3,0),404);
        code(get(null,ROOT+"/"+draft+"/reviews"),401);code(get(owner,ROOT+"/"+draft+"/reviews/invalid"),404);
        code(get(owner,ROOT+"/"+draft+"/reviews?size=51"),400);
        assertThat((List<?>)JsonPath.read(get(owner,ROOT+"/"+draft+"/reviews?page=2&size=1").body(),"$.items")).isEmpty();
        var oldCookies=new HashMap<>(owner.cookies);code(write(owner,"POST","/api/v1/auth/logout",null),204);owner.cookies.putAll(oldCookies);code(withdraw(owner,draft,review,3,0),401);
    }
    @Test void concurrentIdempotencyAndOldWithdrawalCannotWithdrawNewReview() throws Exception {
        String draft=completeDraft(),job=passed(draft),request=UUID.randomUUID().toString();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(1);Callable<HttpResponse<String>> action=()->{ready.await();return review(owner,draft,3,job,request);};
            var a=pool.submit(action);var b=pool.submit(action);ready.countDown();var ra=a.get(30,TimeUnit.SECONDS);var rb=b.get(30,TimeUnit.SECONDS);code(ra,202);code(rb,202);assertThat(ra.body()).isEqualTo(rb.body());
        }
        String first=JsonPath.read(review(owner,draft,3,job,request).body(),"$.reviewId");
        code(review(owner,draft,3,UUID.randomUUID().toString(),request),409);
        code(withdraw(owner,draft,first,3,0),200);
        String second=JsonPath.read(review(owner,draft,3,job,UUID.randomUUID().toString()).body(),"$.reviewId");
        code(withdraw(owner,draft,first,3,0),200);
        assertThat((String)JsonPath.read(get(owner,ROOT+"/"+draft+"/reviews/"+second).body(),"$.review.status")).isEqualTo("PENDING");
        assertThat(get(owner,ROOT+"/"+draft).body()).contains("UNDER_REVIEW");
        assertThat(db().queryForObject("SELECT COUNT(*) FROM content_review WHERE active_draft_id=?",Integer.class,draft)).isEqualTo(1);
        assertThat((Integer)JsonPath.read(get(owner,ROOT+"/"+draft+"/reviews").body(),"$.total")).isEqualTo(2);
    }
    @Test void immutableReviewGrantsCompositeBindingAndUniquePendingAreEnforced() throws Exception {
        String draft=completeDraft(),job=passed(draft);String id=JsonPath.read(review(owner,draft,3,job,UUID.randomUUID().toString()).body(),"$.reviewId");
        var api=database("forgeoj_api","m0-api-test-secret");var worker=database("forgeoj_worker","m0-worker-test-secret");
        for(String column:List.of("snapshot_id","validation_job_id","draft_id","owner_id","draft_version","request_id","review_no")) assertThatThrownBy(()->api.update("UPDATE content_review SET "+column+"="+column+" WHERE 1=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->api.update("DELETE FROM content_review WHERE 1=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->worker.queryForList("SELECT id FROM content_review LIMIT 0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->worker.update("UPDATE content_review SET review_status=review_status WHERE 1=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db().update("UPDATE content_review SET review_status='WITHDRAWN' WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db().update("INSERT INTO content_review(id,draft_id,owner_id,draft_version,validation_job_id,snapshot_id,request_id,review_no) SELECT ?,draft_id,owner_id,draft_version,validation_job_id,snapshot_id,?,2 FROM content_review WHERE id=?",UUID.randomUUID().toString(),UUID.randomUUID().toString(),id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db().update("UPDATE content_review SET draft_version=4 WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void concurrentDifferentRequestsAndEditingCannotCreateMutablePendingReview() throws Exception {
        String draft=completeDraft(),job=passed(draft);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<HttpResponse<String>> action=()->{start.await();return review(owner,draft,3,job,UUID.randomUUID().toString());};
            var a=pool.submit(action);var b=pool.submit(action);start.countDown();var ra=a.get(30,TimeUnit.SECONDS);var rb=b.get(30,TimeUnit.SECONDS);
            assertThat(List.of(ra.statusCode(),rb.statusCode())).containsExactlyInAnyOrder(202,409);
        }
        for(int i=0;i<5;i++) {
            String d=completeDraft(),j=passed(d);var content=(Map<String,Object>)JsonPath.read(get(owner,ROOT+"/"+d).body(),"$.content");
            try(var pool=Executors.newFixedThreadPool(2)) {
                var start=new CountDownLatch(1);
                var send=pool.submit(()->{start.await();return review(owner,d,3,j,UUID.randomUUID().toString());});
                var edit=pool.submit(()->{start.await();return write(owner,"PUT",ROOT+"/"+d,json(Map.of("expectedVersion",3,"content",content)));});
                start.countDown();int sent=send.get(30,TimeUnit.SECONDS).statusCode(),edited=edit.get(30,TimeUnit.SECONDS).statusCode();
                assertThat((sent==202 && edited==409)||(sent==409 && edited==200)).as("send=%s edit=%s",sent,edited).isTrue();
            }
        }
    }
    private String passed(String draft) throws Exception {
        var result=validate(owner,draft,3,UUID.randomUUID().toString());code(result,202);String job=JsonPath.read(result.body(),"$.jobId");
        // This HTTP lifecycle fixture controls the durable terminal fact; actual sandbox PASSED is independently required by E2E.
        db().update("UPDATE content_validation_job SET processing_status='FINISHED',validation_status='PASSED',reference_result='ACCEPTED',solution_result='ACCEPTED',finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",job);return job;
    }
    private HttpResponse<String> review(Browser b,String draft,long version,String job,String request) throws Exception {return write(b,"POST",ROOT+"/"+draft+"/reviews",json(Map.of("expectedVersion",version,"validationJobId",job,"requestId",request)));}
    private HttpResponse<String> withdraw(Browser b,String draft,String review,long version,long reviewVersion) throws Exception {return write(b,"POST",ROOT+"/"+draft+"/reviews/"+review+"/withdraw",json(Map.of("expectedVersion",version,"expectedReviewVersion",reviewVersion)));}
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
