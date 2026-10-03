/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.solution;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.forgeoj.api.auth.AccountService;
import static org.mockito.Mockito.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.flyway.locations=classpath:db/migration,classpath:db/devdata",
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.rabbitmq.listener.direct.auto-startup=false"
})
class SolutionIntegrationTests {
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
    static final String PATH="/api/v1/me/problems/sum-two-integers/solution";
    Browser owner,other;
    @MockitoSpyBean AccountService accounts;
    @BeforeEach void seed() throws Exception {
        var db=db();
        for(String table:List.of("user_solution_early_view","official_problem_solution","submission")) db.update("DELETE FROM "+table);
        db.update("UPDATE user_account SET status='ACTIVE' WHERE id=1");
        db.update("INSERT INTO user_account(id,username,password_hash,status) SELECT 2,'other-learner',password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'");
        db.update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(2)");
        db.update("UPDATE problem SET slug='sum-two-integers',status='ACTIVE',current_judge_version_id=1 WHERE id=1");
        db.update("INSERT IGNORE INTO problem_judge_version(id,problem_id,version_no,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256) SELECT 3,1,2,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256 FROM problem_judge_version WHERE id=1");
        // Synthetic policy fixtures, never production publication/validation evidence.
        db.update("INSERT INTO official_problem_solution(judge_version_id,idea,language,source_code) VALUES(1,'original fixture idea','JAVA_21','SOLUTION_SOURCE_SENTINEL'),(3,'new fixture idea','JAVA_21','NEW_SOLUTION_SENTINEL')");
        owner=login("learner");other=login("other-learner");
    }
    @Test void lockedWhitelistNoAnonymousReadAndNoReadSideEffect() throws Exception {
        var r=get(owner,PATH);code(r,200);
        assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat((Map<String,Object>)JsonPath.read(r.body(),"$")).containsOnlyKeys("judgeVersion","access","solution");
        assertThat(r.body()).contains("LOCKED").doesNotContain("SENTINEL","idea","sourceCode","reference");
        assertThat(db().queryForObject("SELECT COUNT(*) FROM user_solution_early_view",Integer.class)).isZero();
        code(get(null,PATH),401);code(get(owner,PATH.replace("sum-two-integers","missing")),404);
        db().update("UPDATE problem SET status='ARCHIVED' WHERE id=1");code(get(owner,PATH),404);code(confirm(owner,1),404);
    }
    @Test void legacyProblemSlugRemainsUsableWithoutCanonicalFormatRewrite() throws Exception {
        db().update("UPDATE problem SET slug='legacy--fixture-' WHERE id=1");
        String path=PATH.replace("sum-two-integers","legacy--fixture-");
        code(get(owner,"/api/v1/problems/legacy--fixture-"),200);
        var locked=get(owner,path);code(locked,200);assertThat(locked.body()).contains("LOCKED");
        code(write(owner,"POST",path+"/early-view","{\"judgeVersion\":1,\"confirmEarlyView\":true}"),200);
        assertThat(get(owner,path).body()).contains("EARLY_VIEW");
    }
    @Test void ownCurrentAcOnlyAndSourceWhitelist() throws Exception {
        submission(2,1,1,"FINISHED","AC");submission(1,1,3,"FINISHED","AC");submission(1,1,1,"FINISHED","WA");submission(1,1,1,"QUEUED",null);submission(1,1,1,"SYSTEM_ERROR",null);
        assertThat(get(owner,PATH).body()).contains("LOCKED");
        submission(1,1,1,"FINISHED","AC");var r=get(owner,PATH);code(r,200);
        assertThat(r.body()).contains("\"access\":\"AC\"","SOLUTION_SOURCE_SENTINEL").doesNotContain("reference_code","fixture source","test_dataset");
        assertThat((Map<String,Object>)JsonPath.read(r.body(),"$.solution")).containsOnlyKeys("idea","language","sourceCode");
        code(confirm(owner,1),200);assertThat(db().queryForObject("SELECT COUNT(*) FROM user_solution_early_view",Integer.class)).isZero();
    }
    @Test void concurrentIdempotentConfirmationPrivateVersionScopedAndKeepsFirstTime() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<HttpResponse<String>> action=()->{start.await();return confirm(owner,1);};
            var a=pool.submit(action);var b=pool.submit(action);start.countDown();code(a.get(20,TimeUnit.SECONDS),200);code(b.get(20,TimeUnit.SECONDS),200);
        }
        var before=db().queryForList("SELECT * FROM user_solution_early_view");assertThat(before).hasSize(1);
        code(confirm(owner,1),200);assertThat(db().queryForList("SELECT * FROM user_solution_early_view")).isEqualTo(before);
        assertThat(get(owner,PATH).body()).contains("EARLY_VIEW","SOLUTION_SOURCE_SENTINEL");assertThat(get(other,PATH).body()).contains("LOCKED").doesNotContain("SENTINEL");
        db().update("UPDATE problem SET current_judge_version_id=3 WHERE id=1");
        assertThat(get(owner,PATH).body()).contains("LOCKED");code(confirm(owner,1),409);assertThat(db().queryForList("SELECT * FROM user_solution_early_view")).isEqualTo(before);
        code(confirm(owner,2),200);assertThat(get(owner,PATH).body()).contains("NEW_SOLUTION_SENTINEL");
        assertThat(db().queryForObject("SELECT COUNT(*) FROM user_solution_early_view",Integer.class)).isEqualTo(2);assertThat(db().queryForObject("SELECT COUNT(*) FROM submission",Integer.class)).isZero();
    }
    @Test void unavailableNoWriteStrictConfirmationCsrfAndOrigin() throws Exception {
        db().update("DELETE FROM official_problem_solution WHERE judge_version_id=1");assertThat(get(owner,PATH).body()).contains("UNAVAILABLE");code(confirm(owner,1),404);
        for(String body:List.of("{}","{\"judgeVersion\":1,\"confirmEarlyView\":false}","{\"judgeVersion\":1,\"confirmEarlyView\":\"true\"}","{\"judgeVersion\":\"1\",\"confirmEarlyView\":true}","{\"judgeVersion\":1.0,\"confirmEarlyView\":true}","{\"judgeVersion\":2147483648,\"confirmEarlyView\":true}","{\"judgeVersion\":0,\"confirmEarlyView\":true}","{\"judgeVersion\":1,\"confirmEarlyView\":true,\"userId\":2}")) code(write(owner,"POST",PATH+"/early-view",body),400);
        code(send(owner,"POST",PATH+"/early-view","{}",true,false),403);code(send(owner,"POST",PATH+"/early-view","{}",false,true),403);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM user_solution_early_view",Integer.class)).isZero();
    }
    @Test void actualGrantDenialsAndInsertFailureRollback() throws Exception {
        var api=database("forgeoj_api","m0-api-test-secret");var worker=database("forgeoj_worker","m0-worker-test-secret");
        for(String table:List.of("official_problem_solution","user_solution_early_view")) assertThatThrownBy(()->worker.queryForList("SELECT * FROM "+table+" LIMIT 0")).isInstanceOf(DataAccessException.class);
        for(String sql:List.of("UPDATE official_problem_solution SET source_code=source_code WHERE 1=0","DELETE FROM official_problem_solution WHERE 1=0","INSERT INTO official_problem_solution(judge_version_id,idea,language,source_code) VALUES(9,'x','JAVA_21','x')","UPDATE user_solution_early_view SET user_id=user_id WHERE 1=0","DELETE FROM user_solution_early_view WHERE 1=0")) assertThatThrownBy(()->api.execute(sql)).isInstanceOf(DataAccessException.class);
        db().execute("REVOKE INSERT ON forgeoj.user_solution_early_view FROM 'forgeoj_api'@'%'");
        try {code(confirm(owner,1),503);} finally {db().execute("GRANT INSERT ON forgeoj.user_solution_early_view TO 'forgeoj_api'@'%'");}
        assertThat(db().queryForObject("SELECT COUNT(*) FROM user_solution_early_view",Integer.class)).isZero();
    }
    @Test void revokedDuringAuthenticatedWriteAndDisabledReadsFail() throws Exception {
        var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!proceed.await(10,TimeUnit.SECONDS)) throw new AssertionError("write timeout");return call.callRealMethod();}).when(accounts).requireCurrentWrite(1L);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var saved=new Browser();saved.cookies.putAll(owner.cookies);saved.csrf=owner.csrf;var pending=pool.submit(()->confirm(saved,1));
            try {assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();code(write(owner,"POST","/api/v1/auth/logout","{}"),204);} finally {proceed.countDown();}
            code(pending.get(15,TimeUnit.SECONDS),401);code(get(saved,PATH),401);assertThat(db().queryForObject("SELECT COUNT(*) FROM user_solution_early_view",Integer.class)).isZero();
        } finally {reset(accounts);}
        db().update("UPDATE user_account SET status='DISABLED' WHERE id=2");code(get(other,PATH),401);
    }
    private HttpResponse<String> confirm(Browser b,int version) throws Exception {return write(b,"POST",PATH+"/early-view","{\"judgeVersion\":"+version+",\"confirmEarlyView\":true}");}
    private String submission(long user,long problem,long judge,String state,String verdict) {
        String id=UUID.randomUUID().toString();
        db().update("INSERT INTO submission(id,user_id,problem_id,judge_version_id,client_request_id,language,source_code,source_sha256,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256,processing_status,verdict,status_version) SELECT ?,?,?,?,?,'JAVA_21','fixture source',REPEAT('a',64),time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256,?,?,2 FROM problem_judge_version WHERE id=?",id,user,problem,judge,UUID.randomUUID().toString(),state,verdict,judge);
        return id;
    }
    private Browser login(String username) throws Exception {
        var b=new Browser();var anonymous=get(b,"/api/v1/auth/session");b.csrf=JsonPath.read(anonymous.body(),"$.csrf.token");
        var response=write(b,"POST","/api/v1/auth/login","{\"username\":\""+username+"\",\"password\":\"forgeoj-dev-only\"}");code(response,200);b.csrf=JsonPath.read(response.body(),"$.csrf.token");return b;
    }
    private HttpResponse<String> get(Browser b,String path) throws Exception {return send(b,"GET",path,null,false,false);}
    private HttpResponse<String> write(Browser b,String method,String path,String body) throws Exception {return send(b,method,path,body,true,true);}
    private HttpResponse<String> send(Browser b,String method,String path,String body,boolean origin,boolean csrf) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if(b!=null && !b.cookies.isEmpty()) request.header("Cookie",String.join("; ",b.cookies.values()));
        if(origin) request.header("Origin","http://localhost:"+port);if(csrf && b!=null && b.csrf!=null) request.header("X-CSRF-TOKEN",b.csrf);
        var response=client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        if(b!=null) for(String cookie:response.headers().allValues("Set-Cookie")) {String pair=cookie.split(";",2)[0];b.cookies.put(pair.split("=",2)[0],pair);}
        return response;
    }
    private static void code(HttpResponse<String> r,int expected) {assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);if(Set.of(400,401,403,404,503).contains(expected)) assertThat(r.body()).isEmpty();}
    private JdbcTemplate db() {return database("forgeoj_migrator","m0-migrator-test-secret");}
    private JdbcTemplate database(String name,String password) {return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),name,password));}
    private static class Browser {final Map<String,String> cookies=new ConcurrentHashMap<>();String csrf;}
}
