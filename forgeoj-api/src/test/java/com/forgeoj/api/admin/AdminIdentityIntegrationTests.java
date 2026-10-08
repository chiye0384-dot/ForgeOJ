/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"forgeoj.assignments.scheduler.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false","spring.rabbitmq.listener.direct.auto-startup=false"})
class AdminIdentityIntegrationTests {
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql")).withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret").withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",()->"forgeoj_api");r.add("spring.datasource.password",()->"m0-api-test-secret");r.add("spring.flyway.url",MYSQL::getJdbcUrl);r.add("spring.flyway.user",()->"forgeoj_migrator");r.add("spring.flyway.password",()->"m0-migrator-test-secret");}
    @LocalServerPort int port;
    @MockitoSpyBean AdminMapper mapper;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSessions;
    @Autowired com.forgeoj.api.auth.AccountService ordinaryAccounts;
    static final String PASSWORD="public-admin-initial-fixture",NEXT="public-admin-changed-fixture",OTHER="public-admin-next-fixture";
    final HttpClient client=HttpClient.newHttpClient();
    long rootId;
    @BeforeEach void seed() throws Exception {
        reset(mapper);var db=db();for(String table:List.of("admin_audit_event","admin_creation_request","admin_refresh_token","admin_login_session","admin_account"))db.execute("DELETE FROM "+table);db.execute("ALTER TABLE admin_account AUTO_INCREMENT=1");db.update("UPDATE admin_policy_fence SET bootstrapped=FALSE WHERE id=1");
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret")){rootId=AdminProvisioning.bootstrap(c,"Root_admin",PASSWORD,new BCryptPasswordEncoder());}
        db.update("INSERT INTO user_account(id,username,password_hash,status) VALUES(1,'ordinary',?,'ACTIVE') ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash),status='ACTIVE'",new BCryptPasswordEncoder().encode(PASSWORD));db.update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(1)");
    }
    @Test void anonymousAdminSessionIsIndependentAndProtectedRoutesStayClosed() throws Exception {
        var client=HttpClient.newHttpClient();
        var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/admin/auth/session")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"authenticated\":false","\"admin\":null","\"csrf\"").doesNotContain("\"user\"");
        assertThat(response.headers().allValues("Set-Cookie")).anyMatch(value->value.startsWith("FORGEOJ_ADMIN_CSRF=") && value.contains("Path=/api/v1/admin"));
        assertThat(client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/admin/accounts")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }
    @Test void firstLoginMustChangePasswordAndExactWhiteListsNeverExposeSecrets()throws Exception{
        var first=login("root_admin",PASSWORD);var response=get(first,"/accounts");code(response,403);
        Map<String,Object> session=JsonPath.read(get(first,"/auth/session").body(),"$");assertThat(session).containsOnlyKeys("authenticated","admin","csrf");
        Map<String,Object> admin=JsonPath.read(get(first,"/auth/session").body(),"$.admin");assertThat(admin).containsOnlyKeys("id","username","role","mustChangePassword");assertThat(admin.get("mustChangePassword")).isEqualTo(true);
        var saved=first.copy();code(write(first,"POST","/auth/password/change",Map.of("currentPassword",PASSWORD,"password",NEXT)),204);assertAnonymous(saved);code(loginRequest("root_admin",PASSWORD),401);
        var ready=login("root_admin",NEXT);code(get(ready,"/accounts"),200);assertThat(get(ready,"/accounts").body()).doesNotContain("passwordHash","sessionId",PASSWORD,NEXT);
    }
    @Test void ordinaryIdentityCrossJwtAndCsrfCannotCrossTheAdminBoundary()throws Exception{
        var browser=new Browser();code(send(browser,"GET","/api/v1/auth/session",null,null),200);code(send(browser,"POST","/api/v1/auth/login",Map.of("username","ordinary","password",PASSWORD),"http://localhost:"+port),200);String token=browser.cookies.get("FORGEOJ_ACCESS");code(get(browser,"/auth/session"),200);code(get(browser,"/accounts"),403);code(get(browser,"/audit-events"),403);
        code(write(browser,"POST","/accounts",creation("new_reviewer","CONTENT_REVIEWER")),403);code(loginRequest("ordinary",PASSWORD),401);
        var cross=anonymous();cross.cookies.put(AdminCookies.ACCESS,token);code(get(cross,"/accounts"),401);
        var root=ready();var reverse=new Browser();reverse.cookies.put("FORGEOJ_ACCESS",root.cookies.get(AdminCookies.ACCESS));var result=send(reverse,"GET","/api/v1/submissions/00000000-0000-0000-0000-000000000001",null,null);code(result,401);
        var noAdminCsrf=root.copy();noAdminCsrf.csrf="ordinary-csrf";code(write(noAdminCsrf,"POST","/accounts",creation("denied_csrf","CONTENT_REVIEWER")),403);
        long denials=db().queryForObject("SELECT COUNT(*) FROM admin_audit_event WHERE action='ADMIN_ACCESS_DENIED'",Long.class);var noOrigin=send(root,"POST","/api/v1/admin/accounts",creation("denied_origin","OPS_ADMIN"),"http://evil.example");code(noOrigin,403);assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_audit_event WHERE action='ADMIN_ACCESS_DENIED'",Long.class)).isEqualTo(denials+1);
    }
    @Test void bothLimitedRolesAreDeniedEveryAccountMutationAndAuditRead()throws Exception{
        var root=ready();for(String role:List.of("CONTENT_REVIEWER","OPS_ADMIN")){
            String name=role.equals("OPS_ADMIN")?"limited_ops":"limited_review";long id=create(root,name,role);var limited=login(name,PASSWORD);code(write(limited,"POST","/auth/password/change",Map.of("currentPassword",PASSWORD,"password",NEXT)),204);limited=login(name,NEXT);
            code(get(limited,"/accounts"),403);code(get(limited,"/audit-events"),403);code(write(limited,"POST","/accounts",creation("no_create","OPS_ADMIN")),403);
            for(String action:List.of("disable","restore","password/reset"))code(write(limited,"POST","/accounts/"+rootId+"/"+action,Map.of("expectedVersion",1,"reason","scope fixture","password",OTHER)),403);
            code(write(limited,"PUT","/accounts/"+rootId+"/role",Map.of("expectedVersion",1,"reason","scope fixture","role","OPS_ADMIN")),403);
            assertThat(db().queryForObject("SELECT status FROM admin_account WHERE id=?",String.class,id)).isEqualTo("ACTIVE");
        }
    }
    @Test void superCanCreateReplayRoleDisableRestoreResetAndOldSessionsStayRevoked()throws Exception{
        var root=ready();var body=creation("managed_admin","CONTENT_REVIEWER");var first=write(root,"POST","/accounts",body);code(first,201);long id=((Number)read(first,"$.id")).longValue();code(write(root,"POST","/accounts",body),201);assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_account WHERE username='managed_admin'",Integer.class)).isEqualTo(1);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_audit_event WHERE action='ADMIN_CREATE_REPLAY'",Integer.class)).isEqualTo(1);
        var changed=new HashMap<>(body);changed.put("role","OPS_ADMIN");code(write(root,"POST","/accounts",changed),409);
        var old=login("managed_admin",PASSWORD);code(write(old,"POST","/auth/password/change",Map.of("currentPassword",PASSWORD,"password",NEXT)),204);old=login("managed_admin",NEXT);
        code(write(root,"PUT","/accounts/"+id+"/role",mutation(id,"role","OPS_ADMIN")),200);assertAnonymous(old);
        old=login("managed_admin",NEXT);code(write(root,"POST","/accounts/"+id+"/disable",mutation(id,null,null)),200);assertAnonymous(old);code(loginRequest("managed_admin",NEXT),401);
        code(write(root,"POST","/accounts/"+id+"/restore",mutation(id,null,null)),200);assertAnonymous(old);
        var restored=login("managed_admin",NEXT);code(write(root,"POST","/accounts/"+id+"/password/reset",mutation(id,"password",OTHER)),204);assertAnonymous(restored);var reset=login("managed_admin",OTHER);code(get(reset,"/accounts"),403);assertThat(read(get(reset,"/auth/session"),"$.admin.mustChangePassword")).isEqualTo(true);
        assertThat(get(root,"/audit-events?action=ADMIN_RESET_PASSWORD").body()).contains("ADMIN_RESET_PASSWORD").doesNotContain(PASSWORD,NEXT,OTHER,"password_hash");
    }
    @Test void refreshReuseCommitsRevocationAndLeavesAnotherSessionAlive()throws Exception{
        var root=ready();var second=login("root_admin",NEXT);var saved=root.copy();code(write(root,"POST","/auth/refresh",Map.of()),200);assertThat(read(get(root,"/auth/session"),"$.authenticated")).isEqualTo(true);
        code(write(saved,"POST","/auth/refresh",Map.of()),401);assertAnonymous(saved);assertAnonymous(root);assertThat(read(get(second,"/auth/session"),"$.authenticated")).isEqualTo(true);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_audit_event WHERE action='ADMIN_REFRESH_REUSE_REVOKED'",Integer.class)).isEqualTo(1);
    }
    @Test void logoutCurrentRefreshOnlyAndAllLogoutDoNotTouchOrdinarySessions()throws Exception{
        var root=ready();var second=login("root_admin",NEXT);var user=ordinaryAccounts.login("ordinary",PASSWORD);var saved=root.copy();root.cookies.remove(AdminCookies.ACCESS);code(write(root,"POST","/auth/logout",Map.of()),204);assertAnonymous(saved);assertThat(read(get(second,"/auth/session"),"$.authenticated")).isEqualTo(true);
        var third=login("root_admin",NEXT);code(write(second,"POST","/auth/logout-all",Map.of()),204);assertAnonymous(third);assertThat(ordinaryAccounts.authenticated(user.userId(),user.sessionId())).isTrue();
    }
    @Test void currentRoleAndExpiryAreReadFromMySqlNotOldPrincipalClaims()throws Exception{
        var root=ready();db().update("UPDATE admin_account SET role='OPS_ADMIN' WHERE id=?",rootId);code(get(root,"/accounts"),403);assertThat(read(get(root,"/auth/session"),"$.admin.role")).isEqualTo("OPS_ADMIN");
        var original=com.nimbusds.jwt.SignedJWT.parse(root.cookies.get(AdminCookies.ACCESS));var claims=new com.nimbusds.jwt.JWTClaimsSet.Builder(original.getJWTClaimsSet()).claim("role","SUPER_ADMIN").build();var forged=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256),claims);forged.sign(new com.nimbusds.jose.crypto.MACSigner("public-admin-test-only-independent-signing-key".getBytes(java.nio.charset.StandardCharsets.UTF_8)));root.cookies.put(AdminCookies.ACCESS,forged.serialize());code(get(root,"/accounts"),403);
        db().update("UPDATE admin_login_session SET created_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE),expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE admin_id=?",rootId);assertAnonymous(root);code(get(root,"/accounts"),401);
    }
    @Test void revocationBetweenFilterAndTransactionRejectsAnAlreadyAuthenticatedWrite()throws Exception{
        var root=ready();long denials=db().queryForObject("SELECT COUNT(*) FROM admin_audit_event WHERE action='ADMIN_ACCESS_DENIED'",Long.class);
        // Execute the actual mapped SQL; an interface proxy has no concrete real method.
        doAnswer(call->{db().update("UPDATE admin_login_session SET revoked_at=UTC_TIMESTAMP(6) WHERE admin_id=?",rootId);return sqlSessions.getMapper(AdminMapper.class).fence();}).when(mapper).fence();
        try{code(write(root,"POST","/accounts",creation("race_must_not_exist","OPS_ADMIN")),401);}finally{reset(mapper);}
        assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_account WHERE username='race_must_not_exist'",Integer.class)).isZero();assertAnonymous(root);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_audit_event WHERE action='ADMIN_ACCESS_DENIED'",Long.class)).isEqualTo(denials+1);
    }
    @Test void lastSuperProtectionIsCasBoundedAndConcurrentDisablesKeepOneActive()throws Exception{
        var root=ready();code(write(root,"POST","/accounts/"+rootId+"/disable",mutation(rootId,null,null)),409);code(write(root,"PUT","/accounts/"+rootId+"/role",mutation(rootId,"role","OPS_ADMIN")),409);
        long other=create(root,"second_super","SUPER_ADMIN");var second=login("second_super",PASSWORD);code(write(second,"POST","/auth/password/change",Map.of("currentPassword",PASSWORD,"password",NEXT)),204);second=login("second_super",NEXT);
        var bodyA=mutation(other,null,null);var bodyB=mutation(rootId,null,null);var finalSecond=second;var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->{start.await();return write(root,"POST","/accounts/"+other+"/disable",bodyA).statusCode();});var b=pool.submit(()->{start.await();return write(finalSecond,"POST","/accounts/"+rootId+"/disable",bodyB).statusCode();});start.countDown();var codes=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));assertThat(codes).contains(200);assertThat(codes.stream().filter(c->c==200).count()).isEqualTo(1);assertThat(codes).allMatch(c->c==200||c==401||c==409);}
        finally{pool.shutdownNow();}assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_account WHERE role='SUPER_ADMIN' AND status='ACTIVE'",Integer.class)).isEqualTo(1);
    }
    @Test void auditFailureRollsBackCreationRolePasswordAndNoSuccessRecordLeaks()throws Exception{
        var root=ready();long target=create(root,"rollback_admin","CONTENT_REVIEWER");var before=db().queryForMap("SELECT * FROM admin_account WHERE id=?",target);int events=db().queryForObject("SELECT COUNT(*) FROM admin_audit_event",Integer.class);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("public injected audit failure")).when(mapper).audit(any());
        try{code(write(root,"POST","/accounts",creation("rollback_create","OPS_ADMIN")),503);code(write(root,"PUT","/accounts/"+target+"/role",mutation(target,"role","OPS_ADMIN")),503);code(write(root,"POST","/accounts/"+target+"/password/reset",mutation(target,"password",OTHER)),503);code(get(root,"/accounts"),503);}
        finally{reset(mapper);}assertThat(db().queryForMap("SELECT * FROM admin_account WHERE id=?",target)).isEqualTo(before);assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_account WHERE username='rollback_create'",Integer.class)).isZero();assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_audit_event",Integer.class)).isEqualTo(events);
    }
    @Test void databaseAuthenticationFailureReturns503WithoutFallbackOrNewCookies()throws Exception{
        var root=ready();db().execute("REVOKE SELECT ON forgeoj.admin_login_session FROM 'forgeoj_api'@'%'");
        try{var response=get(root,"/accounts");code(response,503);assertThat(response.headers().allValues("Set-Cookie")).isEmpty();}
        finally{db().execute("GRANT SELECT ON forgeoj.admin_login_session TO 'forgeoj_api'@'%'");}code(get(root,"/accounts"),200);
    }
    @Test void realSqlGrantsKeepWorkerOutAndAuditAndBootstrapImmutableToApi()throws Exception{
        var worker=restricted("forgeoj_worker","m0-worker-test-secret");var api=restricted("forgeoj_api","m0-api-test-secret");
        for(String table:List.of("admin_account","admin_policy_fence","admin_login_session","admin_refresh_token","admin_creation_request","admin_audit_event"))assertThatThrownBy(()->worker.queryForList("SELECT * FROM "+table)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        for(String sql:List.of("DELETE FROM admin_account","UPDATE admin_policy_fence SET bootstrapped=FALSE","DELETE FROM admin_audit_event","UPDATE admin_audit_event SET reason='changed'"))assertThatThrownBy(()->api.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void bootstrapRepetitionAndRecoveryHaveDurableFactsAndRevokeOnlyTarget()throws Exception{
        var root=ready();long target=create(root,"recovery_super","SUPER_ADMIN");var old=login("recovery_super",PASSWORD);long count=db().queryForObject("SELECT COUNT(*) FROM admin_account",Long.class);
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret")){
            assertThatThrownBy(()->AdminProvisioning.bootstrap(c,"another_root",OTHER,new BCryptPasswordEncoder())).isInstanceOf(java.sql.SQLException.class);
            AdminProvisioning.recover(c,target,OTHER,"explicit disposable recovery",new BCryptPasswordEncoder());
            long limited=create(root,"non_super_recovery","OPS_ADMIN");assertThatThrownBy(()->AdminProvisioning.recover(c,limited,OTHER,"not super",new BCryptPasswordEncoder())).isInstanceOf(java.sql.SQLException.class);
        }assertAnonymous(old);code(get(root,"/accounts"),200);assertThat(read(get(login("recovery_super",OTHER),"/auth/session"),"$.admin.mustChangePassword")).isEqualTo(true);assertThat(db().queryForObject("SELECT bootstrapped FROM admin_policy_fence",Boolean.class)).isTrue();assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_account",Long.class)).isEqualTo(count+1);assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_audit_event WHERE actor_type='LOCAL_RECOVERY'",Integer.class)).isEqualTo(1);
    }
    @Test void staleVersionsInvalidPaginationAndMissingTargetsFailWithoutWrites()throws Exception{
        var root=ready();long target=create(root,"cas_admin","OPS_ADMIN");code(write(root,"POST","/accounts/"+target+"/disable",Map.of("expectedVersion",99,"reason","stale")),409);code(write(root,"POST","/accounts/not-id/disable",Map.of("expectedVersion",1,"reason","missing")),404);code(get(root,"/accounts?size=51"),400);code(get(root,"/audit-events?page=0"),400);assertThat(db().queryForObject("SELECT version FROM admin_account WHERE id=?",Long.class,target)).isEqualTo(1);
    }
    @Test void publicReviewQueueChecksCurrentRoleAtTheServer()throws Exception{
        var root=ready();code(get(root,"/content-reviews"),200);
        create(root,"reviewer_queue","CONTENT_REVIEWER");create(root,"ops_queue","OPS_ADMIN");
        for(String name:List.of("reviewer_queue","ops_queue")){
            var b=login(name,PASSWORD);code(write(b,"POST","/auth/password/change",Map.of("currentPassword",PASSWORD,"password",NEXT)),204);b=login(name,NEXT);
            code(get(b,"/content-reviews"),name.equals("reviewer_queue")?200:403);
        }
    }
    private JdbcTemplate db(){return restricted("forgeoj_migrator","m0-migrator-test-secret");}
    private JdbcTemplate restricted(String user,String password){return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),user,password));}
    private Map<String,Object> creation(String name,String role){return Map.of("clientRequestId",UUID.randomUUID().toString(),"username",name,"role",role,"password",PASSWORD,"reason","isolated admin fixture");}
    private Map<String,Object> mutation(long id,String key,String value){var result=new HashMap<String,Object>();result.put("expectedVersion",db().queryForObject("SELECT version FROM admin_account WHERE id=?",Long.class,id));result.put("reason","isolated admin fixture");if(key!=null)result.put(key,value);return result;}
    private long create(Browser root,String name,String role)throws Exception{var result=write(root,"POST","/accounts",creation(name,role));code(result,201);return ((Number)read(result,"$.id")).longValue();}
    private Browser anonymous()throws Exception{var b=new Browser();var response=get(b,"/auth/session");code(response,200);return b;}
    private HttpResponse<String> loginRequest(String name,String password)throws Exception{return write(anonymous(),"POST","/auth/login",Map.of("username",name,"password",password));}
    private Browser login(String name,String password)throws Exception{var b=anonymous();code(write(b,"POST","/auth/login",Map.of("username",name,"password",password)),200);return b;}
    private Browser ready()throws Exception{var b=login("root_admin",PASSWORD);code(write(b,"POST","/auth/password/change",Map.of("currentPassword",PASSWORD,"password",NEXT)),204);return login("root_admin",NEXT);}
    private HttpResponse<String> get(Browser b,String path)throws Exception{return send(b,"GET","/api/v1/admin"+path,null,null);}
    private HttpResponse<String> write(Browser b,String method,String path,Object body)throws Exception{return send(b,method,"/api/v1/admin"+path,body,"http://localhost:"+port);}
    private HttpResponse<String> send(Browser b,String method,String path,Object body,String origin)throws Exception{
        var q=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));if(!b.cookies.isEmpty())q.header("Cookie",b.cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("; ")));
        if(body!=null){q.header("Content-Type","application/json").header(path.startsWith("/api/v1/auth/")?"X-CSRF-TOKEN":"X-ADMIN-CSRF-TOKEN",b.csrf==null?"":b.csrf);if(origin!=null)q.header("Origin",origin);q.method(method,HttpRequest.BodyPublishers.ofString(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body)));}else q.GET();
        var result=client.send(q.build(),HttpResponse.BodyHandlers.ofString());for(String value:result.headers().allValues("Set-Cookie")){var c=java.net.HttpCookie.parse(value).getFirst();if(c.getMaxAge()==0)b.cookies.remove(c.getName());else b.cookies.put(c.getName(),c.getValue());}
        if(result.statusCode()==200&&path.contains("/auth/")&&result.body().contains("\"csrf\"")){Object csrfToken=JsonPath.read(result.body(),"$.csrf.token");b.csrf=(String)csrfToken;}return result;
    }
    private Object read(HttpResponse<String> r,String path){return JsonPath.read(r.body(),path);}
    private void code(HttpResponse<String> r,int code){assertThat(r.statusCode()).describedAs("HTTP body %s",r.body()).isEqualTo(code);}
    private void assertAnonymous(Browser b)throws Exception{assertThat(read(get(b,"/auth/session"),"$.authenticated")).isEqualTo(false);}
    private static final class Browser {final Map<String,String> cookies=new HashMap<>();String csrf;Browser copy(){var b=new Browser();b.cookies.putAll(cookies);b.csrf=csrf;return b;}}
}
