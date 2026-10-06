/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

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
    "forgeoj.auth.limits.multiplier=100", "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.rabbitmq.listener.direct.auto-startup=false"
})
class ClassroomProblemIntegrationTests {
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
    static final String OFFICIAL="00000000-0000-0000-0000-000000000001";
    Browser owner,other,third;
    @MockitoSpyBean AccountService accounts;
    @MockitoSpyBean ClassroomProblemMapper privateMapper;
    @MockitoSpyBean com.forgeoj.api.auth.AccountMapper accountMapper;
    @org.springframework.beans.factory.annotation.Autowired org.mybatis.spring.SqlSessionTemplate sqlSessions;
    @BeforeEach void seed() throws Exception {
        var db=db();
        // Finish only this isolated test database's prior queued fixtures before the next case.
        db.update("UPDATE submission SET processing_status='CANCELLED',status_version=status_version+1,finished_at=CURRENT_TIMESTAMP(6) WHERE processing_status='QUEUED'");
        db.update("UPDATE self_test_job SET processing_status='CANCELLED',status_version=status_version+1,finished_at=CURRENT_TIMESTAMP(6),expires_at=TIMESTAMPADD(HOUR,24,CURRENT_TIMESTAMP(6)) WHERE processing_status='QUEUED'");

        db.update("UPDATE user_account SET status='ACTIVE' WHERE id=1");
        for(int id=2;id<=3;id++) {
            db.update("INSERT INTO user_account(id,username,password_hash,status) SELECT ?,?,password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'",id,"class-fixture-"+id);
            db.update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(?)",id);
        }
        owner=login("learner");other=login("class-fixture-2");third=login("class-fixture-3");
    }
    @Test void dualValidationVersionAndPublicationReplayAreMandatory() throws Exception {
        String room=create(owner);var f=fixture(owner);String request=UUID.randomUUID().toString();
        db().update("UPDATE content_validation_job SET processing_status='QUEUED',validation_status=NULL,reference_result=NULL,solution_result=NULL,finished_at=NULL WHERE id=?",f.job());
        code(publish(owner,room,f,request,"AFTER_AC"),409);
        db().update("UPDATE content_validation_job SET processing_status='FINISHED',validation_status='FAILED',reference_result='ACCEPTED',solution_result='WRONG_ANSWER',finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",f.job());
        code(publish(owner,room,f,request,"AFTER_AC"),409);passed(f.job());
        code(publish(owner,room,new Fixture(f.draft(),f.job(),2),request,"AFTER_AC"),409);
        assertThat(race(()->publish(owner,room,f,request,"AFTER_AC"))).containsExactlyInAnyOrder(201,201);
        var a=publish(owner,room,f,request,"AFTER_AC");code(a,201);String slug=JsonPath.read(a.body(),"$.slug");
        assertThat(db().queryForObject("SELECT COUNT(*) FROM classroom_problem WHERE classroom_id=?",Integer.class,room)).isEqualTo(1);
        code(publish(owner,room,f,request,"IMMEDIATE"),409);
        var before=db().queryForMap("SELECT * FROM content_validation_snapshot WHERE id=(SELECT snapshot_id FROM content_validation_job WHERE id=?)",f.job());
        var c=JsonPath.read(get(owner,ROOT+"/"+f.draft()).body(),"$.content");
        code(write(owner,"PUT",ROOT+"/"+f.draft(),json(Map.of("expectedVersion",3,"content",c))),200);
        code(publish(owner,room,f,UUID.randomUUID().toString(),"AFTER_AC"),409);
        code(publish(owner,room,f,request,"AFTER_AC"),201);
        assertThat(db().queryForMap("SELECT * FROM content_validation_snapshot WHERE id=(SELECT snapshot_id FROM content_validation_job WHERE id=?)",f.job())).isEqualTo(before);
        assertThat(get(owner,privatePath(room,slug)).body()).contains("原创：求和").doesNotContain("referenceCode","solutionIdea","solutionCode","expectedOutput","snapshotId");
    }
    @Test void roleAndRoomIsolationClosePublicLibraryListSolutionAndJudgeBypasses() throws Exception {
        String room=create(owner),second=create(other);code(join(other,invite(room,true)),200);
        var f=fixture(owner);String slug=published(room,f,"AFTER_AC"),p=privatePath(room,slug);
        code(get(other,p),200);code(get(other,p+"/maintenance"),403);code(get(third,p),404);code(get(owner,privatePath(second,slug)),404);
        code(publish(other,room,f,UUID.randomUUID().toString(),"AFTER_AC"),403);
        code(get(owner,"/api/v1/problems/"+slug),404);code(get(owner,"/api/v1/me/problems/"+slug+"/solution"),404);
        var publicRequest=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/problems/"+slug+"/submissions"))
                .header("Cookie",String.join("; ",owner.cookies.values())).header("Origin","http://localhost:"+port).header("X-CSRF-TOKEN",owner.csrf)
                .header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("language","JAVA_21","sourceCode",CODE)))).build();
        code(client.send(publicRequest,HttpResponse.BodyHandlers.ofString()),404);
        assertThat(get(owner,"/api/v1/problems?page=1&size=50").body()).doesNotContain(slug);
        code(write(owner,"POST","/api/v1/me/problems/"+slug+"/solution/early-view",json(Map.of("judgeVersion",1,"confirmEarlyView",true))),404);
        var list=write(owner,"POST","/api/v1/me/problem-lists","{\"title\":\"私人题单\"}");code(list,201);
        String listId=JsonPath.read(list.body(),"$.id");
        code(write(owner,"POST","/api/v1/me/problem-lists/"+listId+"/items",json(Map.of("problemSlug",slug,"expectedVersion",1))),404);
        code(get(owner,p+"/maintenance"),200);
        assertThat(get(other,p+"/solution").body()).contains("LOCKED").doesNotContain("独立题解哨兵");
        action(owner,room,"members/2/role","\"role\":\"ASSISTANT\",",200);code(get(other,p+"/maintenance"),200);
        code(get(other,ROOT+"/"+f.draft()),404);
    }
    @Test void assistantExitKeepsClassOwnershipAndCurrentMembershipRevokesAllPrivateReads() throws Exception {
        String room=create(owner);code(join(other,invite(room,true)),200);action(owner,room,"members/2/role","\"role\":\"ASSISTANT\",",200);
        var f=fixture(other);var pub=publish(other,room,f,UUID.randomUUID().toString(),"IMMEDIATE");code(pub,201);String slug=JsonPath.read(pub.body(),"$.slug"),p=privatePath(room,slug);
        assertThat(get(owner,p+"/solution").body()).contains("独立题解哨兵").doesNotContain("参考程序哨兵");
        action(other,room,"leave","",204);
        for(String suffix:List.of("","/maintenance","/solution")) code(get(other,p+suffix),404);
        code(write(other,"POST",p+"/copy","{}"),404);code(get(owner,p+"/maintenance"),200);
        assertThat(db().queryForObject("SELECT created_by FROM classroom_problem WHERE classroom_id=?",Long.class,room)).isEqualTo(2);
        code(join(other,invite(room,true)),200);code(get(other,p+"/maintenance"),403);
        action(owner,room,"members/2/remove","",200);code(get(other,p),404);
    }
    @Test void copyRequiresTeachingAndRevalidationAndArchiveIsReadOnlyAndBlocksDeletion() throws Exception {
        String room=create(owner);var f=fixture(owner);String slug=published(room,f,"AFTER_AC"),p=privatePath(room,slug);
        var copy=write(owner,"POST",p+"/copy","{}");code(copy,200);String draft=JsonPath.read(copy.body(),"$.draft.id");
        assertThat(get(owner,ROOT+"/"+draft+"/tests").body()).contains("3");
        code(publish(owner,room,new Fixture(draft,f.job(),1),UUID.randomUUID().toString(),"AFTER_AC"),409);
        code(write(owner,"DELETE",path(room)+"?expectedVersion="+version(room),null),409);
        code(write(owner,"POST",p+"/archive","{\"expectedVersion\":2}"),409);
        code(write(owner,"POST",p+"/archive","{\"expectedVersion\":1}"),204);
        code(get(owner,p),200);code(submit(owner,p,UUID.randomUUID().toString()),404);
        action(owner,room,"archive","",204);code(get(owner,p+"/maintenance"),200);
        code(publish(owner,room,f,UUID.randomUUID().toString(),"AFTER_AC"),409);code(write(owner,"POST",p+"/copy","{}"),409);
        assertThatThrownBy(()->db().update("DELETE FROM classroom WHERE id=?",room)).isInstanceOf(DataAccessException.class);
    }
    @Test void formalQuotaIdempotencyAndOwnerHistoryRemainIndependentFromMembership() throws Exception {
        String room=create(owner);code(join(other,invite(room,true)),200);String slug=published(room,fixture(owner),"AFTER_AC"),p=privatePath(room,slug),request=UUID.randomUUID().toString();
        var created=submit(other,p,request);code(created,202);String submission=JsonPath.read(created.body(),"$.submissionId");code(submit(other,p,request),202);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM submission WHERE client_request_id=?",Integer.class,request)).isEqualTo(1);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='JUDGE_TASK' AND aggregate_id=(SELECT id FROM judge_task WHERE submission_id=?)",Integer.class,submission)).isEqualTo(1);
        code(submit(other,p,UUID.randomUUID().toString()),202);code(submit(other,p,UUID.randomUUID().toString()),202);code(submit(other,p,UUID.randomUUID().toString()),429);
        code(get(owner,"/api/v1/submissions/"+submission),404);
        db().update("UPDATE submission SET processing_status='FINISHED',verdict='AC',status_version=1,finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",submission);
        assertThat(get(other,p+"/solution").body()).contains("独立题解哨兵");
        action(other,room,"leave","",204);code(submit(other,p,request),404);code(get(other,p+"/solution"),404);
        code(get(other,"/api/v1/submissions/"+submission),200);
        assertThat(get(other,"/api/v1/me/submissions?page=1&size=50").body()).doesNotContain(slug,"原创：求和");
    }
    @Test void columnGrantsAndWorkerBoundaryRetainImmutablePublishedInputs() throws Exception {
        String room=create(owner);published(room,fixture(owner),"AFTER_AC");var api=database("forgeoj_api","m0-api-test-secret");var worker=database("forgeoj_worker","m0-worker-test-secret");
        for(String column:List.of("problem_id","classroom_id","created_by","draft_id","draft_version","validation_job_id","snapshot_id","client_request_id","solution_policy")) assertThatThrownBy(()->api.update("UPDATE classroom_problem SET "+column+"="+column+" WHERE 1=0")).isInstanceOf(DataAccessException.class);
        for(String table:List.of("classroom_problem","classroom","classroom_member","classroom_solution_early_view")) assertThatThrownBy(()->worker.queryForList("SELECT * FROM "+table+" LIMIT 0")).isInstanceOf(DataAccessException.class);
        for(String sql:List.of("DELETE FROM classroom_problem WHERE 1=0","UPDATE problem SET classroom_id=classroom_id WHERE 1=0","UPDATE problem SET scope=scope WHERE 1=0","UPDATE problem_judge_version SET time_limit_ms=time_limit_ms WHERE 1=0","SELECT * FROM problem_test_case LIMIT 0")) assertThatThrownBy(()->api.execute(sql)).isInstanceOf(DataAccessException.class);
        assertThat(worker.queryForObject("SELECT COUNT(*) FROM problem_test_case",Integer.class)).isGreaterThan(0);
    }
    @Test void publicationArchiveRaceAndTransactionFailureCannotCreateHalfPublishedProblem() throws Exception {
        String room=create(owner);var f=fixture(owner);
        assertThat(raceTwo(()->publish(owner,room,f,UUID.randomUUID().toString(),"AFTER_AC"),()->write(owner,"POST",path(room)+"/archive",versionBody(version(room))))).contains(204).allMatch(s->Set.of(201,204,409).contains(s));
        String next=create(owner);doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture injected failure")).when(privateMapper).publish(anyLong(),anyString(),anyLong(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyString());
        long before=db().queryForObject("SELECT COUNT(*) FROM problem",Long.class);
        try {code(publish(owner,next,f,UUID.randomUUID().toString(),"AFTER_AC"),503);assertThat(db().queryForObject("SELECT COUNT(*) FROM problem",Long.class)).isEqualTo(before);assertThat(db().queryForObject("SELECT COUNT(*) FROM problem_judge_version WHERE problem_id NOT IN (SELECT id FROM problem)",Integer.class)).isZero();}finally{reset(privateMapper);}
    }
    @Test void earlySolutionConfirmationIsExplicitPersonalIdempotentAndRevocable() throws Exception {
        String room=create(owner);code(join(other,invite(room,true)),200);String slug=published(room,fixture(owner),"AFTER_AC"),p=privatePath(room,slug);
        int before=db().queryForObject("SELECT COUNT(*) FROM classroom_solution_early_view",Integer.class);
        assertThat(get(other,p+"/solution").body()).contains("LOCKED");
        code(write(other,"POST",p+"/solution/early-view",json(Map.of("expectedVersion",1,"confirmEarlyView",false))),400);
        code(write(other,"POST",p+"/solution/early-view",json(Map.of("expectedVersion",2,"confirmEarlyView",true))),409);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM classroom_solution_early_view",Integer.class)).isEqualTo(before);
        for(int i=0;i<2;i++) {var result=write(other,"POST",p+"/solution/early-view",json(Map.of("expectedVersion",1,"confirmEarlyView",true)));code(result,200);assertThat(result.body()).contains("EARLY_VIEW","独立题解哨兵").doesNotContain("参考程序哨兵");}
        assertThat(db().queryForObject("SELECT COUNT(*) FROM classroom_solution_early_view",Integer.class)).isEqualTo(before+1);
        assertThat(get(owner,p+"/solution").body()).contains("LOCKED");
        action(other,room,"leave","",204);code(get(other,p+"/solution"),404);
        code(write(other,"POST",p+"/solution/early-view",json(Map.of("expectedVersion",1,"confirmEarlyView",true))),404);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM classroom_solution_early_view",Integer.class)).isEqualTo(before+1);
    }
    @Test void privateSelfTestUsesSharedQuotaAndCannotGrantAcOrExpandTeacherAccess() throws Exception {
        String room=create(owner);code(join(other,invite(room,true)),200);String slug=published(room,fixture(owner),"AFTER_AC"),p=privatePath(room,slug),request=UUID.randomUUID().toString();
        var body=json(Map.of("requestId",request,"language","JAVA_21","sourceCode",CODE,"input","1 2\n"));
        int before=db().queryForObject("SELECT COUNT(*) FROM submission",Integer.class);
        var run=write(other,"POST",p+"/self-tests",body);code(run,202);String id=JsonPath.read(run.body(),"$.runId");code(write(other,"POST",p+"/self-tests",body),202);
        code(get(owner,"/api/v1/self-tests/"+id),404);code(get(other,"/api/v1/self-tests/"+id),200);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM submission",Integer.class)).isEqualTo(before);
        assertThat(get(other,p+"/solution").body()).contains("LOCKED");
        action(other,room,"leave","",204);code(write(other,"POST",p+"/self-tests",body),404);
        code(get(other,"/api/v1/self-tests/"+id),200);
    }
    @Test void currentSessionIsRecheckedInsidePrivatePublicationTransaction() throws Exception {
        String room=create(owner);var f=fixture(owner);var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!proceed.await(10,TimeUnit.SECONDS)) throw new AssertionError("timeout");return call.callRealMethod();}).when(accounts).requireCurrentWrite(1L);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(()->publish(owner,room,f,UUID.randomUUID().toString(),"AFTER_AC"));
            try {assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();code(write(owner,"POST","/api/v1/auth/logout","{}"),204);}finally{proceed.countDown();}
            code(pending.get(15,TimeUnit.SECONDS),401);assertThat(db().queryForObject("SELECT COUNT(*) FROM classroom_problem WHERE classroom_id=?",Integer.class,room)).isZero();
        }finally{reset(accounts);}
    }
    static final String ROOT="/api/v1/me/authored-problems";
    static final String CODE="import java.util.Scanner; public class Main { public static void main(String[] args) {Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}";
    record Fixture(String draft,String job,long version) {}
    private Fixture fixture(Browser author) throws Exception {
        var r=write(author,"POST",ROOT,"{\"title\":\"原创：求和\"}");code(r,201);String draft=JsonPath.read(r.body(),"$.draft.id");
        var c=new LinkedHashMap<String,Object>(JsonPath.read(r.body(),"$.content"));var m=new LinkedHashMap<String,Object>((Map<String,Object>)c.get("metadata"));
        m.put("statement","原创：求和");m.put("inputDescription","两个整数");m.put("outputDescription","和");m.put("licenseStatement","原创 disposable fixture");m.put("samples",List.of(Map.of("input","1 2\n","output","3\n")));c.put("metadata",m);c.put("referenceCode",CODE+"\n// 参考程序哨兵");c.put("solutionCode",CODE+"\n// 独立题解哨兵");c.put("solutionIdea","独立题解哨兵");
        code(write(author,"PUT",ROOT+"/"+draft,json(Map.of("expectedVersion",1,"content",c))),200);
        code(write(author,"PUT",ROOT+"/"+draft+"/tests",json(Map.of("expectedVersion",2,"tests",List.of(Map.of("input","1 2\n","expectedOutput","3\n"))))),200);
        var job=write(author,"POST",ROOT+"/"+draft+"/validations",json(Map.of("expectedVersion",3,"requestId",UUID.randomUUID().toString())));code(job,202);String id=JsonPath.read(job.body(),"$.jobId");passed(id);return new Fixture(draft,id,3);
    }
    // These isolated API fixtures verify authorization/state predicates, not sandbox PASSED.
    // The delivery replay must obtain both results from a real Worker before publication.
    private void passed(String job) {db().update("UPDATE content_validation_job SET processing_status='FINISHED',validation_status='PASSED',reference_result='ACCEPTED',solution_result='ACCEPTED',finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",job);}
    private HttpResponse<String> publish(Browser author,String room,Fixture f,String request,String policy) throws Exception {return write(author,"POST",path(room)+"/problems",json(Map.of("draftId",f.draft(),"draftVersion",f.version(),"validationJobId",f.job(),"clientRequestId",request,"solutionPolicy",policy)));}
    private String published(String room,Fixture f,String policy) throws Exception {var r=publish(owner,room,f,UUID.randomUUID().toString(),policy);code(r,201);return JsonPath.read(r.body(),"$.slug");}
    private static String privatePath(String room,String slug) {return path(room)+"/problems/"+slug;}
    private HttpResponse<String> submit(Browser b,String p,String request) throws Exception {return write(b,"POST",p+"/submissions",json(Map.of("clientRequestId",request,"language","JAVA_21","sourceCode",CODE)));}
    private static String json(Object value) {return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value);}
    private String create(Browser b) throws Exception {var r=write(b,"POST","/api/v1/classrooms","{\"title\":\"原创班级\",\"clientRequestId\":\""+UUID.randomUUID()+"\"}");code(r,201);return JsonPath.read(r.body(),"$.id");}
    private static String path(String id) {return "/api/v1/classrooms/"+id;}
    private long version(String id) {return db().queryForObject("SELECT version FROM classroom WHERE id=?",Long.class,id);}
    private String invite(String id,boolean enabled) throws Exception {var r=write(owner,"POST",path(id)+"/invite","{\"enabled\":"+enabled+",\"expectedVersion\":"+version(id)+"}");code(r,200);return JsonPath.read(r.body(),"$.inviteCode");}
    private HttpResponse<String> join(Browser b,String code) throws Exception {return write(b,"POST","/api/v1/classrooms/join","{\"inviteCode\":\""+code+"\"}");}
    private void action(Browser b,String id,String suffix,String extra,int status) throws Exception {code(write(b,"POST",path(id)+"/"+suffix,"{"+extra+"\"expectedVersion\":"+version(id)+"}"),status);}
    private String propose(String id,long target) throws Exception {var r=write(owner,"POST",path(id)+"/transfers","{\"targetUserId\":"+target+",\"clientRequestId\":\""+UUID.randomUUID()+"\",\"expectedVersion\":"+version(id)+"}");code(r,200);return JsonPath.read(r.body(),"$.id");}
    private static String versionBody(long v) {return "{\"expectedVersion\":"+v+"}";}
    private void assertSingleOwner(String id) {assertThat(db().queryForObject("SELECT COUNT(*) FROM classroom c JOIN classroom_member m ON m.classroom_id=c.id AND m.user_id=c.owner_id WHERE c.id=? AND m.role='OWNER' AND m.status='ACTIVE'",Integer.class,id)).isEqualTo(1);assertThat(db().queryForObject("SELECT COUNT(*) FROM classroom_member WHERE classroom_id=? AND role='OWNER' AND status='ACTIVE'",Integer.class,id)).isEqualTo(1);}
    private List<Integer> race(Callable<HttpResponse<String>> action) throws Exception {return raceTwo(action,action);}
    private List<Integer> raceTwo(Callable<HttpResponse<String>> a,Callable<HttpResponse<String>> b) throws Exception {try(var executor=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);var x=executor.submit(()->{start.await();return a.call().statusCode();});var y=executor.submit(()->{start.await();return b.call().statusCode();});start.countDown();return List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));}}
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
