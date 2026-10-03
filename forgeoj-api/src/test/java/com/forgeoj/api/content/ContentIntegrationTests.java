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
class ContentIntegrationTests {
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
        db().update("DELETE FROM authored_problem_test_case");db().update("DELETE FROM authored_problem_draft");
        db().update("UPDATE user_account SET status='ACTIVE' WHERE id=1");
        db().update("INSERT INTO user_account(id,username,password_hash,status) SELECT 2,'other-author',password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'");
        db().update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(2)");owner=login("learner");other=login("other-author");
    }
    @Test void ownerCrudDoesNotPublishAndPreservesSeparatePrivateObjects() throws Exception {
        String id=create();var initial=get(owner,ROOT+"/"+id);code(initial,200);
        var body=new LinkedHashMap<String,Object>(JsonPath.read(initial.body(),"$.content"));
        body.put("referenceCode","PRIVATE_REFERENCE_SENTINEL");body.put("solutionCode","SEPARATE_SOLUTION_SENTINEL");body.put("solutionIdea","原创思路");
        code(write(owner,"PUT",ROOT+"/"+id,json(Map.of("content",body,"expectedVersion",1))),200);
        String detail=get(owner,ROOT+"/"+id).body();assertThat(detail).contains("PRIVATE_REFERENCE_SENTINEL","SEPARATE_SOLUTION_SENTINEL");
        assertThat(get(owner,"/api/v1/problems").body()).doesNotContain(id,"PRIVATE_REFERENCE_SENTINEL","SEPARATE_SOLUTION_SENTINEL");
        assertThat(db().queryForObject("SELECT COUNT(*) FROM problem",Integer.class)).isEqualTo(1);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM submission",Integer.class)).isZero();
        assertThat(get(other,ROOT).body()).doesNotContain(id);assertThat(initial.headers().firstValue("Cache-Control")).contains("no-store");
        code(write(owner,"DELETE",ROOT+"/"+id+"?expectedVersion=2",null),204);code(get(owner,ROOT+"/"+id),404);
    }
    @Test void everyOwnerRouteRejectsOtherAndMissingWithoutMetadata() throws Exception {
        String id=create();for(String candidate:List.of(id,UUID.randomUUID().toString(),"invalid")) {
            code(get(other,ROOT+"/"+candidate),404);code(get(other,ROOT+"/"+candidate+"/tests"),404);
            code(write(other,"PUT",ROOT+"/"+candidate+"/tests",testsBody(1,"private","answer")),404);
            code(write(other,"POST",ROOT+"/"+candidate+"/archive","{\"expectedVersion\":1}"),404);
            code(write(other,"DELETE",ROOT+"/"+candidate+"?expectedVersion=1",null),404);
            code(sendZip(other,candidate,zip("001.in","in","001.out","out"),1),404);
        }
        code(get(null,ROOT+"/"+id),401);code(write(owner,"POST",ROOT,"{\"title\":\"x\",\"ownerId\":2}"),400);
    }
    @Test void zipAndManualReplacementUseSameCompressedIntegrityAndStableOrder() throws Exception {
        String id=create();code(write(owner,"PUT",ROOT+"/"+id+"/tests",testsBody(1,"手工\r\n","输出\n")),200);
        assertThat(get(owner,ROOT+"/"+id+"/tests").body()).contains("手工\\r\\n","输出\\n");
        code(sendZip(owner,id,zip("002.out","answer2\n","001.in","input1\n","002.in","input2\n","001.out","answer1\n"),2),200);
        List<String> inputs=JsonPath.read(get(owner,ROOT+"/"+id+"/tests").body(),"$[*].input");assertThat(inputs).containsExactly("input1\n","input2\n");
        assertThat(db().queryForObject("SELECT HEX(LEFT(input_gzip,2)) FROM authored_problem_test_case WHERE draft_id=? AND sequence_no=1",String.class,id)).isEqualTo("1F8B");
        assertThat(db().queryForObject("SELECT input_bytes FROM authored_problem_test_case WHERE draft_id=? AND sequence_no=1",Long.class,id)).isEqualTo(7);
        code(sendZip(owner,id,zip("../001.in","bad","001.out","bad"),3),400);
        assertThat(get(owner,ROOT+"/"+id+"/tests").body()).contains("input1\\n","input2\\n");
        assertThat((Integer)JsonPath.read(get(owner,ROOT+"/"+id).body(),"$.draft.version")).isEqualTo(3);
    }
    @Test void concurrentReplacementHasOneWinnerAndNoMixedDataset() throws Exception {
        String id=create();try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<Integer> a=()->{start.await();return write(owner,"PUT",ROOT+"/"+id+"/tests",testsBody(1,"A","A-result")).statusCode();};
            Callable<Integer> b=()->{start.await();return write(owner,"PUT",ROOT+"/"+id+"/tests",testsBody(1,"B","B-result")).statusCode();};
            var first=executor.submit(a);var second=executor.submit(b);start.countDown();assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        String tests=get(owner,ROOT+"/"+id+"/tests").body();String input=JsonPath.read(tests,"$[0].input"),output=JsonPath.read(tests,"$[0].expectedOutput");assertThat(output).isEqualTo(input+"-result");
        assertThat((Integer)JsonPath.read(get(owner,ROOT+"/"+id).body(),"$.draft.version")).isEqualTo(2);
    }
    @Test void failedInsertRollsBackDeletedTestsAndVersion() throws Exception {
        String id=create();code(write(owner,"PUT",ROOT+"/"+id+"/tests",testsBody(1,"old","old-answer")),200);
        db().execute("ALTER TABLE authored_problem_test_case ADD CONSTRAINT content_injected_failure CHECK(input_bytes<4)");
        try {code(write(owner,"PUT",ROOT+"/"+id+"/tests",testsBody(2,"new-data","new-answer")),503);}
        finally {db().execute("ALTER TABLE authored_problem_test_case DROP CHECK content_injected_failure");}
        assertThat(get(owner,ROOT+"/"+id+"/tests").body()).contains("old-answer").doesNotContain("new-answer");
        assertThat((Integer)JsonPath.read(get(owner,ROOT+"/"+id).body(),"$.draft.version")).isEqualTo(2);
    }
    @Test void archiveKeepsPrivateTestsAndRejectsFurtherWrites() throws Exception {
        String id=create();code(write(owner,"PUT",ROOT+"/"+id+"/tests",testsBody(1,"retained","answer")),200);
        code(write(owner,"POST",ROOT+"/"+id+"/archive","{\"expectedVersion\":2}"),200);
        assertThat(get(owner,ROOT+"/"+id).body()).contains("ARCHIVED");assertThat(get(owner,ROOT+"/"+id+"/tests").body()).contains("retained");
        code(write(owner,"PUT",ROOT+"/"+id+"/tests",testsBody(3,"changed","changed")),409);code(write(owner,"DELETE",ROOT+"/"+id+"?expectedVersion=3",null),409);
    }
    @Test void strictTypesSizeVersionSessionAndActualRoleDenials() throws Exception {
        String id=create();code(write(owner,"PUT",ROOT+"/"+id+"/tests","{\"tests\":[],\"expectedVersion\":\"1\"}"),400);
        code(write(owner,"PUT",ROOT+"/"+id+"/tests","{\"tests\":[],\"expectedVersion\":0}"),400);
        code(write(owner,"PUT",ROOT+"/"+id+"/tests",testsBody(1,"x".repeat(1048577),"")),400);
        code(write(owner,"POST",ROOT,"{\"title\":\""+"字".repeat(101)+"\"}"),400);
        code(get(owner,ROOT+"?size=51"),400);
        assertThatThrownBy(()->database("forgeoj_worker","m0-worker-test-secret").queryForList("SELECT * FROM authored_problem_draft")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->database("forgeoj_worker","m0-worker-test-secret").queryForList("SELECT * FROM authored_problem_test_case")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->database("forgeoj_api","m0-api-test-secret").update("UPDATE authored_problem_draft SET owner_id=owner_id WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var revokedCookies=new HashMap<>(owner.cookies);code(write(owner,"POST","/api/v1/auth/logout",null),204);
        owner.cookies.clear();owner.cookies.putAll(revokedCookies);
        code(write(owner,"PUT",ROOT+"/"+id+"/tests",testsBody(1,"revoked","")),401);
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
