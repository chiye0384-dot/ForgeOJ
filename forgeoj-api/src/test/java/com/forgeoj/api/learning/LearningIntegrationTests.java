/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.learning;

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
class LearningIntegrationTests {
    @Container static final MySQLContainer MYSQL=new MySQLContainer(DockerImageName.parse(
            "container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret")
            .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",()->"forgeoj_api");r.add("spring.datasource.password",()->"m0-api-test-secret");
        r.add("spring.flyway.url",MYSQL::getJdbcUrl);r.add("spring.flyway.user",()->"forgeoj_migrator");r.add("spring.flyway.password",()->"m0-migrator-test-secret");
    }
    @LocalServerPort int port;
    final HttpClient client=HttpClient.newHttpClient();
    static final String OFFICIAL="00000000-0000-0000-0000-000000000001";
    Browser owner,other;
    @MockitoSpyBean AccountService accounts;
    @BeforeEach void seed() throws Exception {
        var db=db();
        for(String table:List.of("personal_problem_list_item","personal_problem_list","official_problem_list_item","official_problem_list","user_code_draft","submission")) db.update("DELETE FROM "+table);
        db.update("UPDATE user_account SET status='ACTIVE' WHERE id=1");
        db.update("INSERT INTO user_account(id,username,password_hash,status) SELECT 2,'other-learner',password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'");
        db.update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(2)");
        db.update("UPDATE problem SET status='ACTIVE',current_judge_version_id=1 WHERE id=1");
        db.update("INSERT IGNORE INTO problem(id,slug,title,statement_text,input_description,output_description,public_samples_json,status) VALUES(2,'second-fixture','第二道原创题','fixture','in','out',JSON_ARRAY(),'ACTIVE')");
        for(long[] version:List.of(new long[]{2,2,1},new long[]{3,1,2}))
            db.update("INSERT IGNORE INTO problem_judge_version(id,problem_id,version_no,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256) SELECT ?,?,?,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256 FROM problem_judge_version WHERE id=1",version[0],version[1],version[2]);
        db.update("UPDATE problem SET status='ACTIVE',current_judge_version_id=2 WHERE id=2");
        db.update("INSERT INTO official_problem_list(id,title,description) VALUES(?,'官方原创练习','isolated fixture')",OFFICIAL);
        db.update("INSERT INTO official_problem_list_item(id,list_id,problem_id,position) VALUES(?,?,1,1),(?,?,2,2)",UUID.randomUUID().toString(),OFFICIAL,UUID.randomUUID().toString(),OFFICIAL);
        owner=login("learner");other=login("other-learner");
    }
    @Test void privateOwnershipEveryRouteAndIdenticalMissingResponses() throws Exception {
        String id=create();add(id,"sum-two-integers",1);
        String item=JsonPath.read(get(owner,"/api/v1/me/problem-lists/"+id).body(),"$.items[0].itemId");
        for(String target:List.of(id,UUID.randomUUID().toString(),"invalid")) {
            code(get(other,"/api/v1/me/problem-lists/"+target),404);
            code(write(other,"PATCH","/api/v1/me/problem-lists/"+target,"{\"title\":\"other\",\"expectedVersion\":2}"),404);
            code(write(other,"DELETE","/api/v1/me/problem-lists/"+target+"?expectedVersion=2",null),404);
            code(write(other,"POST","/api/v1/me/problem-lists/"+target+"/items","{\"problemSlug\":\"second-fixture\",\"expectedVersion\":2}"),404);
            code(write(other,"DELETE","/api/v1/me/problem-lists/"+target+"/items/"+item+"?expectedVersion=2",null),404);
            code(write(other,"PUT","/api/v1/me/problem-lists/"+target+"/order","{\"itemIds\":[\""+item+"\"],\"expectedVersion\":2}"),404);
        }
        assertThat((Integer)JsonPath.read(get(other,"/api/v1/me/problem-lists?userId=1").body(),"$.total")).isZero();
        code(write(owner,"POST","/api/v1/me/problem-lists","{\"title\":\"bad\",\"ownerId\":2}"),400);
        assertThat(get(owner,"/api/v1/me/problem-lists/"+id).headers().firstValue("Cache-Control")).contains("no-store");
    }
    @Test void orderingPermutationDuplicatesCasAndDeletion() throws Exception {
        String id=create();add(id,"sum-two-integers",1);add(id,"second-fixture",2);
        List<String> ids=JsonPath.read(get(owner,"/api/v1/me/problem-lists/"+id).body(),"$.items[*].itemId");
        String reverse=orderBody(List.of(ids.get(1),ids.get(0)),3);
        code(write(owner,"PUT","/api/v1/me/problem-lists/"+id+"/order",reverse),200);
        assertThat((List<String>)JsonPath.read(get(owner,"/api/v1/me/problem-lists/"+id).body(),"$.items[*].itemId")).containsExactly(ids.get(1),ids.get(0));
        code(write(owner,"PUT","/api/v1/me/problem-lists/"+id+"/order",reverse),409);
        for(var permutation:List.of(List.<String>of(),List.of(ids.get(0)),List.of(ids.get(0),ids.get(0)),List.of(ids.get(0),UUID.randomUUID().toString())))
            code(write(owner,"PUT","/api/v1/me/problem-lists/"+id+"/order",orderBody(permutation,4)),400);
        code(write(owner,"POST","/api/v1/me/problem-lists/"+id+"/items","{\"problemSlug\":\"sum-two-integers\",\"expectedVersion\":4}"),409);
        code(write(owner,"DELETE","/api/v1/me/problem-lists/"+id+"/items/"+ids.get(1)+"?expectedVersion=4",null),200);
        assertThat((List<Integer>)JsonPath.read(get(owner,"/api/v1/me/problem-lists/"+id).body(),"$.items[*].position")).containsExactly(1);
        code(draft(owner,"",0),200);
        code(write(owner,"DELETE","/api/v1/me/problem-lists/"+id+"?expectedVersion=5",null),204);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM user_code_draft",Integer.class)).isEqualTo(1);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM problem",Integer.class)).isEqualTo(2);
    }
    @Test void concurrentListWinnerAndReorderRollback() throws Exception {
        String id=create();add(id,"sum-two-integers",1);add(id,"second-fixture",2);
        assertThat(race(()->write(owner,"PATCH","/api/v1/me/problem-lists/"+id,"{\"title\":\"new\",\"expectedVersion\":3}"))).containsExactlyInAnyOrder(200,409);
        var before=db().queryForList("SELECT id,position FROM personal_problem_list_item WHERE list_id=? ORDER BY position",id);
        var ids=before.stream().map(r->(String)r.get("id")).toList();
        db().execute("ALTER TABLE personal_problem_list_item ADD CONSTRAINT fixture_fail_order CHECK(id <> '"+ids.get(1)+"' OR position <> 1)");
        try {code(write(owner,"PUT","/api/v1/me/problem-lists/"+id+"/order",orderBody(List.of(ids.get(1),ids.get(0)),4)),503);}
        finally {db().execute("ALTER TABLE personal_problem_list_item DROP CHECK fixture_fail_order");}
        assertThat(db().queryForList("SELECT id,position FROM personal_problem_list_item WHERE list_id=? ORDER BY position",id)).isEqualTo(before);
        assertThat(db().queryForObject("SELECT version FROM personal_problem_list WHERE id=?",Long.class,id)).isEqualTo(4);
    }
    @Test void progressOnlyOwnCurrentFinishedAcAndNoPublicPersonalFacts() throws Exception {
        String id=create();add(id,"sum-two-integers",1);add(id,"second-fixture",2);
        submission(1,1,1,"FINISHED","AC");submission(1,1,1,"FINISHED","AC");submission(2,2,2,"FINISHED","AC");
        submission(1,2,2,"FINISHED","WA");submission(1,2,2,"QUEUED",null);submission(1,2,2,"SYSTEM_ERROR",null);
        assertThat(completed(id)).isEqualTo(1);
        var anonymous=get(null,"/api/v1/official-problem-lists/"+OFFICIAL);code(anonymous,200);
        assertThat(anonymous.body()).doesNotContain("completed","userId","ownerId","sourceCode");
        assertThat((Integer)JsonPath.read(get(owner,"/api/v1/me/official-problem-lists/"+OFFICIAL).body(),"$.list.completedCount")).isEqualTo(1);
        db().update("UPDATE problem SET current_judge_version_id=3 WHERE id=1");
        assertThat(completed(id)).isZero();
        assertThat(db().queryForObject("SELECT COUNT(*) FROM submission WHERE judge_version_id=1 AND verdict='AC'",Integer.class)).isEqualTo(2);
        submission(1,1,3,"FINISHED","AC");assertThat(completed(id)).isEqualTo(1);
    }
    @Test void unavailablePlaceholderAndOwnReadonlyDraft() throws Exception {
        String id=create();add(id,"sum-two-integers",1);code(draft(owner,"unfinished",0),200);
        db().update("UPDATE problem SET status='ARCHIVED' WHERE id=1");
        var result=get(owner,"/api/v1/me/problem-lists/"+id);code(result,200);
        Map<String,Object> item=JsonPath.read(result.body(),"$.items[0]");
        assertThat(item.keySet()).containsExactlyInAnyOrder("itemId","position","available");
        assertThat(result.body()).doesNotContain("sum-two-integers","两数之和","judgeVersion");
        assertThat((Integer)JsonPath.read(result.body(),"$.list.availableCount")).isZero();
        assertThat((Boolean)JsonPath.read(get(owner,"/api/v1/me/problems/sum-two-integers/draft").body(),"$.editable")).isFalse();
        code(draft(owner,"changed",1),404);code(get(other,"/api/v1/me/problems/sum-two-integers/draft"),404);
        code(write(owner,"POST","/api/v1/me/problem-lists/"+id+"/items","{\"problemSlug\":\"sum-two-integers\",\"expectedVersion\":2}"),404);
        code(write(owner,"DELETE","/api/v1/me/problem-lists/"+id+"/items/"+item.get("itemId")+"?expectedVersion=2",null),200);
        db().update("UPDATE official_problem_list SET status='ARCHIVED' WHERE id=?",OFFICIAL);
        code(get(null,"/api/v1/official-problem-lists/"+OFFICIAL),404);
    }
    @Test void initialAndExistingDraftRacesIsolationAndValidation() throws Exception {
        assertThat((Integer)JsonPath.read(get(owner,"/api/v1/me/problems/sum-two-integers/draft").body(),"$.version")).isZero();
        assertThat(db().queryForObject("SELECT COUNT(*) FROM user_code_draft",Integer.class)).isZero();
        assertThat(race(()->draft(owner,"incomplete Java",0))).containsExactlyInAnyOrder(200,409);
        assertThat(race(()->draft(owner,"second version",1))).containsExactlyInAnyOrder(200,409);
        assertThat((String)JsonPath.read(get(owner,"/api/v1/me/problems/sum-two-integers/draft").body(),"$.sourceCode")).isEqualTo("second version");
        assertThat((Integer)JsonPath.read(get(other,"/api/v1/me/problems/sum-two-integers/draft").body(),"$.version")).isZero();
        code(draft(other,"other source",0),200);code(draft(owner,"",2),200);
        code(draft(owner,"x".repeat(65537),3),400);
        for(String body:List.of("{\"language\":\"JAVA_21\",\"sourceCode\":\"\\u0000\",\"expectedVersion\":3}","{\"language\":\"JAVA_21\",\"sourceCode\":\"\\ud800\",\"expectedVersion\":3}","{\"language\":\"PYTHON\",\"sourceCode\":\"x\",\"expectedVersion\":3}"))
            code(write(owner,"PUT","/api/v1/me/problems/sum-two-integers/draft",body),400);
    }
    @Test void originCsrfLogoutAndDisabledAccount() throws Exception {
        code(send(owner,"POST","/api/v1/me/problem-lists","{\"title\":\"test\"}",false,true),403);
        code(send(owner,"POST","/api/v1/me/problem-lists","{\"title\":\"test\"}",true,false),403);
        code(get(null,"/api/v1/me/problem-lists"),401);
        code(write(owner,"POST","/api/v1/official-problem-lists","{\"title\":\"test\"}"),405);
        // Keep the saved, still-unexpired access cookie to prove DB revocation.
        var saved=new Browser();saved.cookies.putAll(owner.cookies);saved.csrf=owner.csrf;
        code(write(owner,"POST","/api/v1/auth/logout","{}"),204);
        code(draft(saved,"after logout",0),401);code(write(saved,"POST","/api/v1/me/problem-lists","{\"title\":\"test\"}"),401);
        db().update("UPDATE user_account SET status='DISABLED' WHERE id=2");code(draft(other,"disabled",0),401);
    }
    @Test void historyOrderWhitelistAndUnavailableRedaction() throws Exception {
        String a=submission(1,1,1,"FINISHED","AC"),b=submission(1,1,3,"FINISHED","WA");submission(2,2,2,"FINISHED","AC");
        db().update("UPDATE submission SET created_at='2026-10-03 00:00:00.123456' WHERE user_id=1");
        var result=get(owner,"/api/v1/me/submissions?size=1&userId=2");code(result,200);
        assertThat((Integer)JsonPath.read(result.body(),"$.total")).isEqualTo(2);
        var sorted=new ArrayList<>(List.of(a,b));sorted.sort(Comparator.reverseOrder());
        assertThat((String)JsonPath.read(result.body(),"$.items[0].submissionId")).isEqualTo(sorted.getFirst());
        assertThat((String)JsonPath.read(get(owner,"/api/v1/me/submissions?page=2&size=1").body(),"$.items[0].submissionId")).isEqualTo(sorted.getLast());
        Map<String,Object> item=JsonPath.read(result.body(),"$.items[0]");
        assertThat(item.keySet()).containsExactlyInAnyOrder("submissionId","createdAt","language","processingStatus","statusVersion","verdict","judgeVersion","problem");
        assertThat(result.body()).doesNotContain("source","sha256","attempt","diagnostic","userId");
        db().update("UPDATE problem SET current_judge_version_id=NULL WHERE id=1");
        assertThat((Object)JsonPath.read(get(owner,"/api/v1/me/submissions").body(),"$.items[0].problem")).isNull();
        code(get(owner,"/api/v1/me/submissions?problemSlug=sum-two-integers"),404);
    }
    @Test void leastPrivilegeAndActualDatabaseFailure503() throws Exception {
        var api=database("forgeoj_api","m0-api-test-secret");var worker=database("forgeoj_worker","m0-worker-test-secret");
        for(String table:List.of("personal_problem_list","personal_problem_list_item","official_problem_list","official_problem_list_item","user_code_draft"))
            assertThatThrownBy(()->worker.queryForList("SELECT * FROM "+table+" LIMIT 0")).isInstanceOf(DataAccessException.class);
        for(String sql:List.of("DELETE FROM official_problem_list WHERE 1=0","UPDATE official_problem_list SET title=title WHERE 1=0","UPDATE personal_problem_list SET owner_id=owner_id WHERE 1=0","UPDATE user_code_draft SET user_id=user_id WHERE 1=0","DELETE FROM user_code_draft WHERE 1=0","SELECT * FROM problem_test_case LIMIT 0"))
            assertThatThrownBy(()->api.execute(sql)).isInstanceOf(DataAccessException.class);
        db().execute("REVOKE SELECT ON forgeoj.user_code_draft FROM 'forgeoj_api'@'%'");
        try {code(get(owner,"/api/v1/me/problems/sum-two-integers/draft"),503);}
        finally {db().execute("GRANT SELECT ON forgeoj.user_code_draft TO 'forgeoj_api'@'%'");}
        code(get(owner,"/api/v1/me/problem-lists?size=51"),400);code(get(owner,"/api/v1/me/submissions?page=abc"),400);code(get(null,"/error"),401);
    }
    @Test void revocationDuringAuthenticatedWriteIsRecheckedInsideTransaction() throws Exception {
        var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!proceed.await(10,TimeUnit.SECONDS)) throw new AssertionError("write gate timeout");return call.callRealMethod();}).when(accounts).requireCurrentWrite(1L);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(()->draft(owner,"must never be stored",0));
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                // The request has already passed the JWT filter. Real logout commits before its business lock.
                code(write(owner,"POST","/api/v1/auth/logout","{}"),204);
            } finally {proceed.countDown();}
            code(pending.get(15,TimeUnit.SECONDS),401);
            assertThat(db().queryForObject("SELECT COUNT(*) FROM user_code_draft",Integer.class)).isZero();
        } finally {reset(accounts);}
    }
    @Test void boundsSafeVersionsAndUtf8AreEnforcedWithoutWritingInvalidData() throws Exception {
        code(draft(owner,"汉".repeat(21846),0),400);
        code(draft(owner,"汉".repeat(21845),0),200);
        db().update("UPDATE user_code_draft SET version=9007199254740991 WHERE user_id=1");
        code(draft(owner,"overflow",9007199254740991L),409);
        code(draft(owner,"outside JS range",9007199254740992L),400);
        var id=create();
        db().update("UPDATE personal_problem_list SET version=9007199254740991 WHERE id=?",id);
        code(write(owner,"PATCH","/api/v1/me/problem-lists/"+id,"{\"title\":\"overflow\",\"expectedVersion\":9007199254740991}"),409);
        code(write(owner,"POST","/api/v1/me/problem-lists","{\"title\":\""+"字".repeat(65)+"\"}"),400);
        for(int i=1;i<100;i++) db().update("INSERT INTO personal_problem_list(id,owner_id,title) VALUES(?,1,'bounded fixture')",UUID.randomUUID().toString());
        code(write(owner,"POST","/api/v1/me/problem-lists","{\"title\":\"limit\"}"),409);
        assertThat((List<?>)JsonPath.read(get(owner,"/api/v1/me/problem-lists?page=2147483647&size=50").body(),"$.items")).isEmpty();
    }
    private int completed(String id) throws Exception {var result=get(owner,"/api/v1/me/problem-lists/"+id);code(result,200);return JsonPath.read(result.body(),"$.list.completedCount");}
    private List<Integer> race(Callable<HttpResponse<String>> action) throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<Integer> call=()->{start.await();return action.call().statusCode();};
            var a=executor.submit(call);var b=executor.submit(call);start.countDown();return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }
    private String create() throws Exception {var r=write(owner,"POST","/api/v1/me/problem-lists","{\"title\":\"本人练习\"}");code(r,201);return JsonPath.read(r.body(),"$.id");}
    private void add(String id,String slug,long version) throws Exception {code(write(owner,"POST","/api/v1/me/problem-lists/"+id+"/items","{\"problemSlug\":\""+slug+"\",\"expectedVersion\":"+version+"}"),200);}
    private static String orderBody(List<String> ids,long version) {return "{\"itemIds\":["+String.join(",",ids.stream().map(id->"\""+id+"\"").toList())+"],\"expectedVersion\":"+version+"}";}
    private HttpResponse<String> draft(Browser b,String source,long version) throws Exception {return write(b,"PUT","/api/v1/me/problems/sum-two-integers/draft","{\"language\":\"JAVA_21\",\"sourceCode\":\""+source+"\",\"expectedVersion\":"+version+"}");}
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
