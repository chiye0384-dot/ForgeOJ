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
class ContentOutputIntegrationTests {
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
        db().update("DELETE FROM content_output_acceptance");db().update("DELETE FROM content_output_preview_case");db().update("DELETE FROM content_review");db().update("DELETE FROM outbox_event WHERE aggregate_type='CONTENT_VALIDATION'");db().update("DELETE FROM content_validation_attempt");db().update("DELETE FROM content_validation_job");db().update("DELETE FROM content_validation_test_case");db().update("DELETE FROM content_validation_snapshot");
        db().update("DELETE FROM authored_problem_test_case");db().update("DELETE FROM authored_problem_draft");
        db().update("UPDATE user_account SET status='ACTIVE' WHERE id=1");
        db().update("INSERT INTO user_account(id,username,password_hash,status) SELECT 2,'other-author',password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'");
        db().update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(2)");owner=login("learner");other=login("other-author");
    }


    @Test void successfulPreviewDoesNotReplaceUntilConfirmationAndReplayNeverOverwritesLaterEdits() throws Exception {
        String draft=completeDraft(),request=UUID.randomUUID().toString();var created=preview(draft,3,request);code(created,202);String job=JsonPath.read(created.body(),"$.jobId");
        assertThat(created.headers().firstValue("Cache-Control")).hasValue("no-store");
        code(preview(draft,3,request),202);assertThat(JsonPath.<String>read(preview(draft,3,request).body(),"$.jobId")).isEqualTo(job);
        code(validate(owner,draft,3,request),409);code(get(owner,ROOT+"/"+draft+"/validations/"+job),404);
        assertThat(JsonPath.<Number>read(get(owner,ROOT+"/"+draft+"/validations").body(),"$.total").intValue()).isZero();
        finishPreview(job,"8\n");
        var detail=get(owner,ROOT+"/"+draft+"/output-previews/"+job);code(detail,200);assertThat(detail.body()).contains("generatedOutput","previousOutput");
        assertThat(get(owner,ROOT+"/"+draft+"/tests").body()).contains("3\\n").doesNotContain("8\\n");
        code(review(owner,draft,3,job,UUID.randomUUID().toString()),409);
        code(get(other,ROOT+"/"+draft+"/output-previews/"+job),404);code(get(new Browser(),ROOT+"/"+draft+"/output-previews"),401);
        code(write(other,"POST",ROOT+"/"+draft+"/output-previews/"+job+"/accept","{\"expectedVersion\":3}"),404);
        var accepted=accept(draft,job,3);code(accepted,200);assertThat(JsonPath.<Number>read(accepted.body(),"$.appliedVersion").intValue()).isEqualTo(4);
        assertThat(get(owner,ROOT+"/"+draft+"/tests").body()).contains("8\\n");
        code(write(owner,"PUT",ROOT+"/"+draft+"/tests",testsBody(4,"1 2\n","later answer")),200);
        code(accept(draft,job,3),200);code(accept(draft,job,4),409);
        assertThat(get(owner,ROOT+"/"+draft+"/tests").body()).contains("later answer");
        assertThat(db().queryForObject("SELECT validation_status FROM content_validation_job WHERE id=?",String.class,job)).isNull();
        assertThat(db().queryForObject("SELECT solution_result FROM content_validation_job WHERE id=?",String.class,job)).isNull();
        assertThat(db().queryForObject("SELECT COUNT(*) FROM submission",Integer.class)).isZero();
    }
    @Test void failedStaleArchivedAndPendingPreviewsCannotConfirmAndTamperRollsBack() throws Exception {
        String draft=completeDraft(),job=JsonPath.read(preview(draft,3,UUID.randomUUID().toString()).body(),"$.jobId");
        code(accept(draft,job,3),409);
        finishPreview(job,"8\n");db().update("UPDATE content_output_preview_case SET output_sha256=REPEAT('0',64) WHERE job_id=?",job);
        code(accept(draft,job,3),503);assertThat(JsonPath.<Number>read(get(owner,ROOT+"/"+draft).body(),"$.draft.version").intValue()).isEqualTo(3);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM content_output_acceptance",Integer.class)).isZero();
        db().update("UPDATE content_output_preview_case SET output_sha256=? WHERE job_id=?",ContentValidationService.hash("8\n"),job);
        String passed=passed(draft);var pending=review(owner,draft,3,passed,UUID.randomUUID().toString());code(pending,202);
        code(accept(draft,job,3),409);code(preview(draft,3,UUID.randomUUID().toString()),409);
        code(withdraw(owner,draft,JsonPath.read(pending.body(),"$.reviewId"),3,0),200);
        code(write(owner,"PUT",ROOT+"/"+draft+"/tests",testsBody(3,"4 5","manual")),200);code(accept(draft,job,3),409);
        code(write(owner,"POST",ROOT+"/"+draft+"/archive","{\"expectedVersion\":4}"),200);code(accept(draft,job,4),409);
        code(get(owner,ROOT+"/"+draft+"/output-previews/"+job),200);
    }
    @Test void strictBoundsSharedQuotaPurposeAndDatabasePermissions() throws Exception {
        String draft=completeDraft(),path=ROOT+"/"+draft+"/output-previews";
        code(write(owner,"POST",path,"{\"expectedVersion\":3,\"requestId\":\"bad\",\"extra\":1}"),400);
        code(write(owner,"POST",path,"{\"expectedVersion\":3.0,\"requestId\":\""+UUID.randomUUID()+"\"}"),400);
        code(get(owner,path+"?size=51"),400);
        for(int i=0;i<3;i++) code(preview(draft,3,UUID.randomUUID().toString()),202);code(preview(draft,3,UUID.randomUUID().toString()),429);
        JdbcTemplate api=database("forgeoj_api","m0-api-test-secret"),worker=database("forgeoj_worker","m0-worker-test-secret");
        for(String query:List.of("INSERT INTO content_output_preview_case(job_id) VALUES('not-an-id')","UPDATE content_output_preview_case SET output_bytes=0 WHERE 1=0","DELETE FROM content_output_acceptance WHERE 1=0","UPDATE content_validation_job SET execution_kind='VALIDATE' WHERE 1=0")) assertThatThrownBy(()->api.execute(query)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        for(String query:List.of("SELECT job_id FROM content_output_acceptance LIMIT 0","UPDATE content_output_preview_case SET output_bytes=0 WHERE 1=0","UPDATE content_validation_job SET execution_kind='VALIDATE' WHERE 1=0")) assertThatThrownBy(()->worker.execute(query)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        String job=db().queryForObject("SELECT id FROM content_validation_job LIMIT 1",String.class);
        assertThatThrownBy(()->db().update("UPDATE content_validation_job SET processing_status='FINISHED',reference_result='ACCEPTED',solution_result='ACCEPTED',validation_status='PASSED',finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",job)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db().update("UPDATE content_validation_job SET execution_kind='VALIDATE' WHERE id=?",job)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void concurrentConfirmationHasOneReceiptAndOneVersionIncrement() throws Exception {
        String draft=completeDraft(),job=JsonPath.read(preview(draft,3,UUID.randomUUID().toString()).body(),"$.jobId");finishPreview(job,"6\n");
        try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);Callable<HttpResponse<String>> run=()->{start.await();return accept(draft,job,3);};var a=pool.submit(run);var b=pool.submit(run);start.countDown();code(a.get(30,TimeUnit.SECONDS),200);code(b.get(30,TimeUnit.SECONDS),200);}
        assertThat(db().queryForObject("SELECT COUNT(*) FROM content_output_acceptance",Integer.class)).isEqualTo(1);
        assertThat(JsonPath.<Number>read(get(owner,ROOT+"/"+draft).body(),"$.draft.version").intValue()).isEqualTo(4);
    }
    private HttpResponse<String> preview(String draft,long version,String request)throws Exception{return write(owner,"POST",ROOT+"/"+draft+"/output-previews",json(Map.of("expectedVersion",version,"requestId",request)));}
    private HttpResponse<String> accept(String draft,String job,long version)throws Exception{return write(owner,"POST",ROOT+"/"+draft+"/output-previews/"+job+"/accept",json(Map.of("expectedVersion",version)));}
    private void finishPreview(String job,String output)throws Exception {
        // Synthetic durable result for HTTP lifecycle tests; Worker execution is tested separately.
        String snapshot=db().queryForObject("SELECT snapshot_id FROM content_validation_job WHERE id=?",String.class,job);var bytes=output.getBytes(java.nio.charset.StandardCharsets.UTF_8);var compressed=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(compressed)){gzip.write(bytes);}
        db().update("INSERT INTO content_output_preview_case(job_id,snapshot_id,sequence_no,output_gzip,output_bytes,output_sha256) VALUES(?,?,1,?,?,?)",job,snapshot,compressed.toByteArray(),bytes.length,ContentValidationService.hash(output));
        db().update("UPDATE content_validation_job SET processing_status='FINISHED',reference_result='ACCEPTED',finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",job);
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
