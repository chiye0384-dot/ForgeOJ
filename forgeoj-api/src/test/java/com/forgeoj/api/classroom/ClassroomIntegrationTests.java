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
class ClassroomIntegrationTests {
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
    @MockitoSpyBean com.forgeoj.api.auth.AccountMapper accountMapper;
    @org.springframework.beans.factory.annotation.Autowired org.mybatis.spring.SqlSessionTemplate sqlSessions;
    @BeforeEach void seed() throws Exception {
        var db=db();
        for(String table:List.of("classroom_transfer","classroom_member","classroom","classroom_creation_request")) db.update("DELETE FROM "+table);
        db.update("UPDATE user_account SET status='ACTIVE' WHERE id=1");
        for(int id=2;id<=3;id++) {
            db.update("INSERT INTO user_account(id,username,password_hash,status) SELECT ?,?,password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'",id,"class-fixture-"+id);
            db.update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(?)",id);
        }
        owner=login("learner");other=login("class-fixture-2");third=login("class-fixture-3");
    }
    @Test void createReplayJoinRaceAndRoleScope() throws Exception {
        String request=UUID.randomUUID().toString(),body="{\"title\":\"原创班级\",\"clientRequestId\":\""+request+"\"}";
        assertThat(race(()->write(owner,"POST","/api/v1/classrooms",body))).containsExactlyInAnyOrder(201,201);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM classroom",Integer.class)).isEqualTo(1);
        String id=db().queryForObject("SELECT id FROM classroom",String.class),code=invite(id,true);
        assertThat(race(()->join(other,code))).containsExactlyInAnyOrder(200,200);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM classroom_member",Integer.class)).isEqualTo(2);
        action(owner,id,"members/2/role","\"role\":\"ASSISTANT\",",200);
        for(String suffix:List.of("archive","restore","invite","members/1/remove","transfers")) {
            String extras=suffix.equals("invite")?"\"enabled\":false,":suffix.equals("transfers")?"\"targetUserId\":1,\"clientRequestId\":\""+UUID.randomUUID()+"\",":"";
            action(other,id,suffix,extras,403);
        }
        String second=create(other);
        assertThat((String)JsonPath.read(get(other,path(second)).body(),"$.role")).isEqualTo("OWNER");
        assertThat((String)JsonPath.read(get(other,path(id)).body(),"$.role")).isEqualTo("ASSISTANT");
        assertThat(get(other,path(id)).body()).doesNotContain("inviteSha256","email","password","sessionId");
        assertThat(get(owner,path(id)).headers().firstValue("Cache-Control")).contains("no-store");
    }
    @Test void invitationRotationLeftAndRemovedCannotSelfRestore() throws Exception {
        String id=create(owner),old=invite(id,true);code(join(other,old),200);
        action(owner,id,"members/2/role","\"role\":\"ASSISTANT\",",200);
        var joined=db().queryForObject("SELECT joined_at FROM classroom_member WHERE user_id=2",java.time.LocalDateTime.class);
        action(other,id,"leave","",204);code(get(other,path(id)),404);code(join(other,old),200);
        assertThat(db().queryForObject("SELECT role FROM classroom_member WHERE user_id=2",String.class)).isEqualTo("MEMBER");
        assertThat(db().queryForObject("SELECT joined_at FROM classroom_member WHERE user_id=2",java.time.LocalDateTime.class)).isEqualTo(joined);
        action(owner,id,"members/2/remove","",200);code(join(other,old),404);
        String rotated=invite(id,true);code(join(other,rotated),404);code(join(third,old),404);
        action(owner,id,"members/2/restore","",200);code(get(other,path(id)),200);
        invite(id,false);code(join(third,rotated),404);code(get(other,path(id)),200);
        action(owner,id,"leave","",409);
        code(write(owner,"DELETE",path(id)+"?expectedVersion="+version(id),null),409);
    }
    @Test void transferAcceptWithdrawAndExitRacesKeepSingleOwner() throws Exception {
        String id=create(owner),code=invite(id,true);code(join(other,code),200);code(join(third,code),200);
        String transfer=propose(id,2),body=versionBody(version(id));
        assertThat(race(()->write(other,"POST",path(id)+"/transfers/"+transfer+"/accept",body))).containsExactlyInAnyOrder(200,200);
        assertThat(db().queryForObject("SELECT owner_id FROM classroom WHERE id=?",Long.class,id)).isEqualTo(2);
        assertThat(db().queryForObject("SELECT role FROM classroom_member WHERE user_id=1",String.class)).isEqualTo("ASSISTANT");
        assertSingleOwner(id);
        var next=write(other,"POST",path(id)+"/transfers","{\"targetUserId\":3,\"clientRequestId\":\""+UUID.randomUUID()+"\",\"expectedVersion\":"+version(id)+"}");code(next,200);
        String nextId=JsonPath.read(next.body(),"$.id");
        code(write(other,"POST",path(id)+"/transfers/"+transfer+"/accept",body),200);
        assertThat(db().queryForObject("SELECT status FROM classroom_transfer WHERE id=?",String.class,nextId)).isEqualTo("PENDING");
        long v=version(id);
        var statuses=raceTwo(()->write(third,"POST",path(id)+"/transfers/"+nextId+"/accept",versionBody(v)),()->write(other,"POST",path(id)+"/transfers/"+nextId+"/withdraw",versionBody(v)));
        assertThat(statuses).contains(200,409);assertSingleOwner(id);
        // A third member's exit races target acceptance with the same version.
        var currentOwner=db().queryForObject("SELECT owner_id FROM classroom WHERE id=?",Long.class,id)==2?other:third;
        var target=currentOwner==other?third:other;long targetId=currentOwner==other?3:2;
        var proposed=write(currentOwner,"POST",path(id)+"/transfers","{\"targetUserId\":"+targetId+",\"clientRequestId\":\""+UUID.randomUUID()+"\",\"expectedVersion\":"+version(id)+"}");code(proposed,200);
        String last=JsonPath.read(proposed.body(),"$.id");long lastVersion=version(id);
        assertThat(raceTwo(()->write(target,"POST",path(id)+"/transfers/"+last+"/accept",versionBody(lastVersion)),()->write(target,"POST",path(id)+"/leave",versionBody(lastVersion)))).containsAnyOf(200,204).contains(409);
        assertSingleOwner(id);
    }
    @Test void archiveOwnerExitRestoreAndEmptyDeletionDoNotEraseHistory() throws Exception {
        String id=create(owner),code=invite(id,true);code(join(other,code),200);String transfer=propose(id,2);
        action(owner,id,"archive","",204);code(join(third,code),404);
        assertThat(db().queryForObject("SELECT status FROM classroom_transfer WHERE id=?",String.class,transfer)).isEqualTo("WITHDRAWN");
        action(owner,id,"members/2/role","\"role\":\"ASSISTANT\",",409);
        action(owner,id,"leave","",204);code(get(owner,path(id)),404);
        action(owner,id,"restore","",204);assertSingleOwner(id);code(get(owner,path(id)),200);
        action(other,id,"leave","",204);code(write(owner,"DELETE",path(id)+"?expectedVersion="+version(id),null),409);
        String request=UUID.randomUUID().toString(),body="{\"title\":\"空班\",\"clientRequestId\":\""+request+"\"}";
        var created=write(owner,"POST","/api/v1/classrooms",body);code(created,201);String empty=JsonPath.read(created.body(),"$.id");
        code(write(owner,"DELETE",path(empty)+"?expectedVersion=1",null),204);code(write(owner,"POST","/api/v1/classrooms",body),404);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM classroom_creation_request WHERE client_request_id=?",Integer.class,request)).isEqualTo(1);
    }
    @Test void ownershipMissingResponsesCsrfRevocationAndDatabaseGrants() throws Exception {
        String id=create(owner);
        for(String target:List.of(id,UUID.randomUUID().toString(),"invalid")) {
            code(get(other,path(target)),404);code(write(other,"PATCH",path(target),"{\"title\":\"stolen\",\"expectedVersion\":1}"),404);
            code(write(other,"DELETE",path(target)+"?expectedVersion=1",null),404);
        }
        code(send(owner,"POST",path(id)+"/archive",versionBody(1),false,true),403);
        code(send(owner,"POST",path(id)+"/archive",versionBody(1),true,false),403);
        code(get(null,path(id)),401);
        var worker=database("forgeoj_worker","m0-worker-test-secret");var api=database("forgeoj_api","m0-api-test-secret");
        for(String table:List.of("classroom","classroom_member","classroom_transfer","classroom_creation_request")) assertThatThrownBy(()->worker.queryForList("SELECT * FROM "+table+" LIMIT 0")).isInstanceOf(DataAccessException.class);
        for(String sql:List.of("UPDATE classroom SET id=id WHERE 1=0","UPDATE classroom_member SET user_id=user_id WHERE 1=0","UPDATE classroom_transfer SET target_user_id=target_user_id WHERE 1=0","DELETE FROM classroom_transfer WHERE 1=0","DELETE FROM classroom_creation_request WHERE 1=0")) assertThatThrownBy(()->api.execute(sql)).isInstanceOf(DataAccessException.class);
        db().update("UPDATE user_account SET status='DISABLED' WHERE id=1");code(get(owner,path(id)),401);code(write(owner,"POST",path(id)+"/archive",versionBody(1)),401);
    }
    @Test void currentSessionIsRecheckedAndPartialTransferRollsBack() throws Exception {
        String id=create(owner),code=invite(id,true);code(join(other,code),200);String transfer=propose(id,2);
        db().execute("ALTER TABLE classroom_member ADD CONSTRAINT fixture_no_new_owner CHECK(user_id<>2 OR role<>'OWNER')");
        try {code(write(other,"POST",path(id)+"/transfers/"+transfer+"/accept",versionBody(version(id))),503);}
        finally {db().execute("ALTER TABLE classroom_member DROP CHECK fixture_no_new_owner");}
        assertSingleOwner(id);assertThat(db().queryForObject("SELECT status FROM classroom_transfer WHERE id=?",String.class,transfer)).isEqualTo("PENDING");
        var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!proceed.await(10,TimeUnit.SECONDS)) throw new AssertionError("timeout");return call.callRealMethod();}).when(accounts).requireCurrentWrite(1L);
        try(var executor=Executors.newSingleThreadExecutor()) {
            long v=version(id);var pending=executor.submit(()->write(owner,"POST",path(id)+"/archive",versionBody(v)));
            try {assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();code(write(owner,"POST","/api/v1/auth/logout","{}"),204);} finally {proceed.countDown();}
            code(pending.get(15,TimeUnit.SECONDS),401);
            assertThat(db().queryForObject("SELECT status FROM classroom WHERE id=?",String.class,id)).isEqualTo("ACTIVE");
        } finally {reset(accounts);}
    }
    @Test void joinRotationAndSameVersionChangesSerializeWithoutStaleReads() throws Exception {
        String id=create(owner),code=invite(id,true);long v=version(id);
        var results=raceTwo(()->join(other,code),()->write(owner,"POST",path(id)+"/invite","{\"enabled\":false,\"expectedVersion\":"+v+"}"));
        assertThat(results).isIn(List.of(200,409),List.of(404,200));
        long next=version(id);
        assertThat(race(()->write(owner,"PATCH",path(id),"{\"title\":\"新名称\",\"expectedVersion\":"+next+"}"))).containsExactlyInAnyOrder(200,409);
        if(results.get(0)==404) code(join(other,invite(id,true)),200);
        long tVersion=version(id);String request=UUID.randomUUID().toString();
        String transfer="{\"targetUserId\":2,\"clientRequestId\":\""+request+"\",\"expectedVersion\":"+tVersion+"}";
        assertThat(race(()->write(owner,"POST",path(id)+"/transfers",transfer))).containsExactlyInAnyOrder(200,200);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM classroom_transfer WHERE status='PENDING'",Integer.class)).isEqualTo(1);
        code(write(owner,"POST",path(id)+"/transfers","{\"targetUserId\":3,\"clientRequestId\":\""+request+"\",\"expectedVersion\":"+version(id)+"}"),409);
        assertSingleOwner(id);
    }
    @Test void strictFieldsSafeVersionsBoundsAndCreationReplayPayload() throws Exception {
        String request=UUID.randomUUID().toString();
        String body="{\"title\":\"原创班级\",\"clientRequestId\":\""+request+"\"}";
        code(write(owner,"POST","/api/v1/classrooms",body),201);
        code(write(owner,"POST","/api/v1/classrooms",body.replace("原创班级","不同班级")),409);
        code(write(owner,"POST","/api/v1/classrooms",body.replace("原创班级","字".repeat(65))),400);
        code(write(owner,"POST","/api/v1/classrooms",body.replace("}",",\"ownerId\":2}")),400);
        String id=db().queryForObject("SELECT id FROM classroom",String.class);
        code(get(owner,"/api/v1/me/classrooms?size=51"),400);code(get(owner,"/api/v1/me/classrooms?page=0"),400);
        assertThat((List<?>)JsonPath.read(get(owner,"/api/v1/me/classrooms?page=2147483647&size=50").body(),"$.items")).isEmpty();
        code(write(owner,"POST",path(id)+"/archive",versionBody(9007199254740992L)),400);
        db().update("UPDATE classroom SET version=9007199254740991 WHERE id=?",id);
        code(write(owner,"POST",path(id)+"/archive",versionBody(9007199254740991L)),409);
        assertThatThrownBy(()->db().update("INSERT INTO classroom_member(classroom_id,user_id,role) VALUES(?,2,'OWNER')",id)).isInstanceOf(DataAccessException.class);
    }
    @Test void targetAccountForeignKeyLockIsAcquiredBeforeClassroom() throws Exception {
        String id=create(owner),code=invite(id,true);code(join(other,code),200);
        var reached=new CountDownLatch(1);
        // A MyBatis mapper is a JDK proxy; delegate through the real SqlSessionTemplate
        // rather than Mockito callRealMethod on the proxy's interface method.
        doAnswer(call->{reached.countDown();return sqlSessions.getMapper(com.forgeoj.api.auth.AccountMapper.class).lockAccount(2L);}).when(accountMapper).lockAccount(2L);
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret");
        try(var blocker=source.getConnection();var observer=source.getConnection();var executor=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);observer.setAutoCommit(false);
            try(var statement=blocker.createStatement()) {statement.executeQuery("SELECT id FROM user_account WHERE id=2 FOR UPDATE").close();}
            long v=version(id);var pending=executor.submit(()->write(owner,"POST",path(id)+"/transfers","{\"targetUserId\":2,\"clientRequestId\":\""+UUID.randomUUID()+"\",\"expectedVersion\":"+v+"}"));
            try {
                assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();
                // The target account is blocked, but classroom must still be available.
                try(var statement=observer.prepareStatement("SELECT id FROM classroom WHERE id=? FOR UPDATE NOWAIT")) {statement.setString(1,id);assertThat(statement.executeQuery().next()).isTrue();}
                observer.commit();
            } finally {blocker.commit();}
            code(pending.get(15,TimeUnit.SECONDS),200);assertSingleOwner(id);
        } finally {reset(accountMapper);}
    }
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

