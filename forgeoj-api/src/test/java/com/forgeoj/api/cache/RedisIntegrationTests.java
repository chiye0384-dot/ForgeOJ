/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.cache;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.forgeoj.api.auth.AccountRateLimiter;
import com.forgeoj.api.problem.ProblemMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;
import tools.jackson.databind.ObjectMapper;

/** Fixed real Redis/MySQL plus actual HTTP filters; fixture root never acts as a business account. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.flyway.locations=classpath:db/migration,classpath:db/devdata",
    "forgeoj.redis.enabled=true","forgeoj.redis.cooldown-ms=100",
    "forgeoj.redis.invalidation-delay-ms=3600000","forgeoj.assignments.scheduler.enabled=false",
    "spring.rabbitmq.listener.simple.auto-startup=false","spring.rabbitmq.listener.direct.auto-startup=false"})
class RedisIntegrationTests {
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(
        DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
        .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret")
        .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(
        DockerImageName.parse("redis:7.2.16-alpine@sha256:29e8589c3f9ba699b5f7aa4b3c7733c58852a3626439e619aa0ee78de08c6ca0")).withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",()->"forgeoj_api");r.add("spring.datasource.password",()->"m0-api-test-secret");
        r.add("spring.flyway.url",MYSQL::getJdbcUrl);r.add("spring.flyway.user",()->"forgeoj_migrator");r.add("spring.flyway.password",()->"m0-migrator-test-secret");
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
    }
    @LocalServerPort int port;
    @Autowired RedisSupport support;
    @Autowired PublicCache cache;
    @Autowired SessionCache sessions;
    @Autowired CacheMapper epochs;
    @Autowired CacheInvalidationProcessor invalidations;
    @Autowired CacheInvalidations changes;
    @Autowired javax.sql.DataSource apiData;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean ProblemMapper problems;
    @MockitoSpyBean SessionCacheMapper sessionFacts;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static final String OFFICIAL="00000000-0000-0000-0000-000000000523";
    @BeforeEach void resetFixture()throws Exception {
        reset(problems,sessionFacts);
        REDIS.execInContainer("redis-cli","FLUSHDB");
        change("UPDATE problem SET status='ACTIVE',title='Redis public fixture' WHERE id=1");
        db().update("INSERT INTO official_problem_list(id,title,description) VALUES(?,'Redis official fixture','Public only') ON DUPLICATE KEY UPDATE status='ACTIVE',title='Redis official fixture'",OFFICIAL);
        db().update("INSERT IGNORE INTO official_problem_list_item(id,list_id,problem_id,position) VALUES(?,?,1,1)",UUID.randomUUID().toString(),OFFICIAL);
    }
    @Test void publicDetailHitsAreWhitelistedAndInvalidationChangesEpochImmediately()throws Exception {
        var first=get("/api/v1/problems/sum-two-integers");code(first,200);code(get("/api/v1/problems/sum-two-integers"),200);
        verify(problems,times(1)).findActiveBySlug("sum-two-integers");
        Map<String,Object> dto=JsonPath.read(first.body(),"$");
        assertThat(dto).containsOnlyKeys("slug","title","statement","inputDescription","outputDescription","publicSamples","judgeVersion","resourceLimits");
        long old=epochs.publicRevision();String key=publicKey(old,"problem:sum-two-integers");
        assertThat(redis.hasKey(key)).isTrue();
        change("UPDATE problem SET title='Fresh committed text' WHERE id=1");
        assertThat(get("/api/v1/problems/sum-two-integers").body()).contains("Fresh committed text");
        processOld(old);assertThat(redis.hasKey(key)).isFalse();
        change("UPDATE problem SET status='ARCHIVED' WHERE id=1");
        code(get("/api/v1/problems/sum-two-integers"),404);
        assertThat(get("/api/v1/problems/sum-two-integers").body()).doesNotContain("Fresh committed text");
    }
    @Test void negativeCacheExpiresSoonAndMalformedValuesRebuildFromMysql()throws Exception {
        code(get("/api/v1/problems/missing-redis-fixture"),404);code(get("/api/v1/problems/missing-redis-fixture"),404);
        verify(problems,times(1)).findActiveBySlug("missing-redis-fixture");
        var negative=publicKey(epochs.publicRevision(),"problem:missing-redis-fixture");
        assertThat(redis.getExpire(negative,TimeUnit.MILLISECONDS)).isBetween(1L,12000L);
        code(get("/api/v1/problems/sum-two-integers"),200);
        String key=publicKey(epochs.publicRevision(),"problem:sum-two-integers");
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(230000L,300000L);
        redis.opsForValue().set(key,"{not-json");
        code(get("/api/v1/problems/sum-two-integers"),200);
        verify(problems,times(2)).findActiveBySlug("sum-two-integers");
        redis.opsForValue().set(key,json.writeValueAsString(new PublicCache.Entry(RedisSupport.digest("problem:sum-two-integers"),"{}","invalid-checksum")));
        code(get("/api/v1/problems/sum-two-integers"),200);
        verify(problems,times(3)).findActiveBySlug("sum-two-integers");
        Set<Long> ttls=new HashSet<>();
        for(int i=0;i<20;i++){String request="jitter-"+i;cache.read(request,String.class,()->"public fixture");ttls.add(redis.getExpire(publicKey(epochs.publicRevision(),request),TimeUnit.SECONDS));}
        assertThat(ttls.size()).isGreaterThan(1);
    }
    @Test void officialCacheExcludesProgressAndNeverKeepsHiddenProblemsOrArchivedLists()throws Exception {
        String path="/api/v1/official-problem-lists/"+OFFICIAL;
        var first=get(path);code(first,200);assertThat(get(path).body()).isEqualTo(first.body());
        assertThat(first.body()).doesNotContain("completed","sourceCode","idea","password");
        code(get("/api/v1/official-problem-lists"),200);code(get("/api/v1/official-problem-lists"),200);
        change("UPDATE problem SET status='ARCHIVED' WHERE id=1");
        var hidden=get(path);code(hidden,200);assertThat(hidden.body()).doesNotContain("sum-two-integers","Redis public fixture");
        change("UPDATE official_problem_list SET status='ARCHIVED',version=version+1 WHERE id=?",OFFICIAL);
        code(get(path),404);
    }
    @Test void rollbackCannotAdvanceEpochOrCreateInvalidationAndOutOfOrderReplayCannotDeleteNewCache()throws Exception {
        long before=epochs.publicRevision();
        long count=db().queryForObject("SELECT COUNT(*) FROM cache_invalidation_outbox",Long.class);
        new TransactionTemplate(manager).execute(s->{dbApi().update("UPDATE problem SET title='rolled back cache fixture' WHERE id=1");changes.publicChanged();s.setRollbackOnly();return null;});
        assertThat(epochs.publicRevision()).isEqualTo(before);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM cache_invalidation_outbox",Long.class)).isEqualTo(count);
        code(get("/api/v1/problems/sum-two-integers"),200);
        change("UPDATE problem SET title='epoch B' WHERE id=1");long b=epochs.publicRevision();code(get("/api/v1/problems/sum-two-integers"),200);
        change("UPDATE problem SET title='epoch C' WHERE id=1");long c=epochs.publicRevision();code(get("/api/v1/problems/sum-two-integers"),200);
        processOld(b);processOld(before);processOld(before);
        assertThat(redis.hasKey(publicKey(c,"problem:sum-two-integers"))).isTrue();
        assertThat(redis.hasKey(publicKey(b,"problem:sum-two-integers"))).isFalse();
        assertThat(get("/api/v1/problems/sum-two-integers").body()).contains("epoch C");
    }
    @Test void hotspotHasSingleLocalRebuildAndExpiredTokenCannotPublishOrReleaseNewLease()throws Exception {
        AtomicInteger loads=new AtomicInteger();
        var gate=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(8)){
            var tasks=new ArrayList<Future<String>>();
            for(int i=0;i<8;i++)tasks.add(executor.submit(()->{gate.await();return cache.read("hotspot-test",String.class,()->{loads.incrementAndGet();try{Thread.sleep(40);}catch(InterruptedException e){Thread.currentThread().interrupt();}return "safe-public-value";});}));
            gate.countDown();for(var task:tasks)assertThat(task.get(10,TimeUnit.SECONDS)).isEqualTo("safe-public-value");
        }
        assertThat(loads).hasValue(1);
        String lock=RedisSupport.PREFIX+"lease-test",key=RedisSupport.PREFIX+"public:999999:lease-test";
        String expired=support.acquire(lock,Duration.ofMillis(40));assertThat(expired).isNotNull();
        Thread.sleep(60);String current=support.acquire(lock,Duration.ofSeconds(3));assertThat(current).isNotNull();
        assertThat(support.publish(lock,expired,key,PublicCache.index(999999),999999,"\"stale\"",Duration.ofSeconds(10))).isFalse();
        support.release(lock,expired);assertThat(redis.opsForValue().get(lock)).isEqualTo(current);
        assertThat(support.publish(lock,current,key,PublicCache.index(999999),999999,"\"current\"",Duration.ofSeconds(10))).isTrue();
        support.release(lock,current);
        support.invalidate(PublicCache.index(999999),999999);
        String retired=support.acquire(lock,Duration.ofSeconds(3));assertThat(support.publish(lock,retired,key,PublicCache.index(999999),999999,"\"late\"",Duration.ofSeconds(10))).isFalse();
    }
    @Test void sharedRateIsAtomicAcrossIndependentInstancesAndFlushDoesNotResetLocalBudget()throws Exception {
        var one=new AccountRateLimiter(1,support);var two=new AccountRateLimiter(1,support);String key="shared:"+UUID.randomUUID();
        one.check(key,3,300);two.check(key,3,300);one.check(key,3,300);
        assertThatThrownBy(()->two.check(key,3,300)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(429));
        String local="flush:"+UUID.randomUUID();one.check(local,2,300);one.check(local,2,300);
        REDIS.execInContainer("redis-cli","FLUSHDB");
        assertThatThrownBy(()->one.check(local,2,300)).isInstanceOf(ResponseStatusException.class);
        String concurrent="concurrent:"+UUID.randomUUID();AtomicInteger admitted=new AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(8)){
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<30;i++)tasks.add(executor.submit(()->{if(Boolean.TRUE.equals(support.allow(concurrent,5,300)))admitted.incrementAndGet();}));
            for(var task:tasks)task.get(10,TimeUnit.SECONDS);
        }assertThat(admitted).hasValue(5);
    }
    @Test void cachedOrdinaryAndAdminIdentityStillRecheckMysqlRevocationAndCurrentRole()throws Exception {
        var db=db();long user=520;
        db.update("INSERT INTO user_account(id,username,password_hash,status) VALUES(?,'redis_identity','private-password-hash','ACTIVE') ON DUPLICATE KEY UPDATE status='ACTIVE'",user);
        String sid=UUID.randomUUID().toString();db.update("INSERT INTO login_session(id,user_id,expires_at) VALUES(?,?,TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)))",sid,user);
        assertThat(sessions.ordinary(user,sid)).isPresent();assertThat(sessions.ordinary(user,sid)).isPresent();
        verify(sessionFacts,times(1)).ordinaryName(user);
        String identityKey=RedisSupport.PREFIX+"session:ordinary:"+user+":1:"+RedisSupport.digest(sid);
        redis.opsForValue().set(identityKey,"null");
        assertThat(sessions.ordinary(user,sid)).isPresent();
        redis.opsForValue().set(identityKey,"{invalid");
        assertThat(sessions.ordinary(user,sid)).isPresent();
        verify(sessionFacts,times(3)).ordinaryName(user);
        db.update("UPDATE login_session SET revoked_at=UTC_TIMESTAMP(6) WHERE id=?",sid);assertThat(sessions.ordinary(user,sid)).isEmpty();
        db.update("INSERT INTO admin_account(id,username,password_hash,status,role,must_change_password,created_at,updated_at) VALUES(520,'redis_admin','private-admin-password','ACTIVE','OPS_ADMIN',FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE status='ACTIVE',role='OPS_ADMIN',must_change_password=FALSE");
        String adminSid=UUID.randomUUID().toString();db.update("INSERT INTO admin_login_session(id,admin_id,created_at,expires_at) VALUES(?,520,UTC_TIMESTAMP(6),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)))",adminSid);
        assertThat(sessions.admin(520,adminSid).orElseThrow().role()).isEqualTo("OPS_ADMIN");
        assertThat(sessions.admin(520,adminSid).orElseThrow().role()).isEqualTo("OPS_ADMIN");
        verify(sessionFacts,times(1)).adminName(520);
        db.update("UPDATE admin_account SET role='CONTENT_REVIEWER',version=version+1 WHERE id=520");
        assertThat(sessions.admin(520,adminSid).orElseThrow().role()).isEqualTo("CONTENT_REVIEWER");
        db.update("UPDATE admin_login_session SET revoked_at=UTC_TIMESTAMP(6) WHERE id=?",adminSid);
        assertThat(sessions.admin(520,adminSid)).isEmpty();
        for(String key:redis.keys(RedisSupport.PREFIX+"session:*")){
            assertThat(key).doesNotContain(sid,adminSid);
            assertThat(redis.opsForValue().get(key)).doesNotContain("password","token","role","email","private");
        }
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture DB unavailable")).when(sessionFacts).ordinary(sid);
        assertThatThrownBy(()->sessions.ordinary(user,sid)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void stoppedRedisFallsBackThenRecoversAndPendingOutboxIsReplayed()throws Exception {
        code(get("/api/v1/problems/sum-two-integers"),200);long old=epochs.publicRevision();
        var limiter=new AccountRateLimiter(1,support);String rate="outage:"+UUID.randomUUID();limiter.check(rate,2,300);
        // Pause only the exact Testcontainers-owned Redis process; preserve its mapped port for recovery.
        var docker=org.testcontainers.DockerClientFactory.instance().client();String owned=REDIS.getContainerId();
        docker.pauseContainerCmd(owned).exec();
        try {
            change("UPDATE problem SET title='Mysql during outage' WHERE id=1");
            code(get("/api/v1/problems/sum-two-integers"),200);assertThat(get("/api/v1/problems/sum-two-integers").body()).contains("Mysql during outage");
            limiter.check(rate,2,300);assertThatThrownBy(()->limiter.check(rate,2,300)).isInstanceOf(ResponseStatusException.class);
            processOld(old);
            assertThat(db().queryForObject("SELECT error_code FROM cache_invalidation_outbox WHERE old_revision=?",String.class,old)).isEqualTo("REDIS_UNAVAILABLE");
        } finally {docker.unpauseContainerCmd(owned).exec();}
        Thread.sleep(180);
        db().update("UPDATE cache_invalidation_outbox SET next_attempt_at=UTC_TIMESTAMP(6) WHERE old_revision=?",old);
        processOld(old);assertThat(db().queryForObject("SELECT delivered_at IS NOT NULL FROM cache_invalidation_outbox WHERE old_revision=?",Boolean.class,old)).isTrue();
        code(get("/api/v1/problems/sum-two-integers"),200);
        assertThat(redis.hasKey(publicKey(epochs.publicRevision(),"problem:sum-two-integers"))).isTrue();
    }
    @Test void cacheTablesAndEpochCannotBeMutatedByApiOrReadByWorker(){
        assertThatThrownBy(()->dbApi().update("DELETE FROM cache_epoch")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->dbApi().update("DELETE FROM cache_invalidation_outbox")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var worker=restricted("forgeoj_worker","m0-worker-test-secret");
        assertThatThrownBy(()->worker.queryForList("SELECT * FROM cache_epoch")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->worker.queryForList("SELECT * FROM cache_invalidation_outbox")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void actualAuthenticatedArchiveWritesSameTransactionOutboxAndCachedSessionCannotOutliveLogout()throws Exception {
        String password="redis-http-fixture-password";
        db().update("INSERT INTO admin_account(id,username,password_hash,status,role,must_change_password,created_at,updated_at) VALUES(521,'redis_reviewer',?,'ACTIVE','CONTENT_REVIEWER',FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash),status='ACTIVE',role='CONTENT_REVIEWER',must_change_password=FALSE",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password));
        var browser=new Browser();
        code(send(browser,"/api/v1/admin/auth/session",null),200);
        code(send(browser,"/api/v1/admin/auth/login",Map.of("username","redis_reviewer","password",password)),200);
        code(send(browser,"/api/v1/admin/public-problems",null),200);
        long version=db().queryForObject("SELECT version FROM public_problem_governance WHERE problem_id=1",Long.class);
        long before=epochs.publicRevision();code(get("/api/v1/problems/sum-two-integers"),200);
        // Real least-privilege SQL failure must roll back the public change, epoch and durable audit.
        db().execute("REVOKE INSERT ON forgeoj.cache_invalidation_outbox FROM 'forgeoj_api'@'%'");
        try {
            code(send(browser,"/api/v1/admin/public-problems/1/archive",Map.of("expectedVersion",version,"reason","Redis HTTP rollback fixture")),503);
            assertThat(epochs.publicRevision()).isEqualTo(before);
            assertThat(db().queryForObject("SELECT status FROM problem WHERE id=1",String.class)).isEqualTo("ACTIVE");
            assertThat(db().queryForObject("SELECT version FROM public_problem_governance WHERE problem_id=1",Long.class)).isEqualTo(version);
            assertThat(db().queryForObject("SELECT COUNT(*) FROM cache_invalidation_outbox WHERE old_revision=?",Long.class,before)).isZero();
        } finally {db().execute("GRANT INSERT ON forgeoj.cache_invalidation_outbox TO 'forgeoj_api'@'%'");}
        code(send(browser,"/api/v1/admin/public-problems/1/archive",Map.of("expectedVersion",version,"reason","Redis HTTP transaction fixture")),200);
        assertThat(epochs.publicRevision()).isEqualTo(before+1);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM cache_invalidation_outbox WHERE old_revision=?",Long.class,before)).isEqualTo(1);
        code(get("/api/v1/problems/sum-two-integers"),404);
        code(send(browser,"/api/v1/admin/auth/logout",Map.of()),204);
        code(send(browser,"/api/v1/admin/public-problems",null),401);
    }
    @Test void lostOrContendedRedisHintStillUsesMysqlSameKeyAndQuota()throws Exception {
        String password="redis-submit-fixture-password";long user=522;
        db().update("INSERT INTO user_account(id,username,password_hash,status) VALUES(?,'redis_submit',?,'ACTIVE') ON DUPLICATE KEY UPDATE status='ACTIVE',password_hash=VALUES(password_hash)",user,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password));
        db().update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(?)",user);
        db().update("UPDATE submission SET processing_status='CANCELLED',status_version=status_version+1,finished_at=UTC_TIMESTAMP(6) WHERE user_id=? AND processing_status='QUEUED'",user);
        var browser=new Browser();code(send(browser,"/api/v1/auth/session",null),200);code(send(browser,"/api/v1/auth/login",Map.of("username","redis_submit","password",password)),200);
        String request=UUID.randomUUID().toString();Map<String,Object> body=Map.of("language","JAVA_21","sourceCode","public class Main { public static void main(String[] args) {} }");
        String inflight=RedisSupport.PREFIX+"inflight:"+RedisSupport.digest(user+":"+request);
        String held=support.acquire(inflight,Duration.ofSeconds(2));
        var first=submit(browser,request,body);code(first,202);support.release(inflight,held);
        // A populated identity cache cannot bypass a failed authority read, including response policy.
        assertThat(redis.keys(RedisSupport.PREFIX+"session:ordinary:"+user+":*")).isNotEmpty();
        String submissionId=JsonPath.read(first.body(),"$.submissionId");
        db().execute("REVOKE SELECT ON forgeoj.login_session FROM 'forgeoj_api'@'%'");
        try {
            var unavailable=send(browser,"/api/v1/submissions/"+submissionId,null);
            code(unavailable,503);
            assertThat(unavailable.body()).isEmpty();
            assertThat(unavailable.headers().firstValue("Cache-Control")).hasValue("no-store");
            code(get("/api/v1/problems/sum-two-integers"),200);
        } finally {db().execute("GRANT SELECT ON forgeoj.login_session TO 'forgeoj_api'@'%'");}
        REDIS.execInContainer("redis-cli","FLUSHDB");
        var replay=submit(browser,request,body);code(replay,202);assertThat(replay.body()).isEqualTo(first.body());
        assertThat(db().queryForObject("SELECT COUNT(*) FROM submission WHERE user_id=? AND client_request_id=?",Integer.class,user,request)).isEqualTo(1);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM outbox_event o JOIN judge_task j ON j.id=o.aggregate_id JOIN submission s ON s.id=j.submission_id WHERE s.user_id=? AND s.client_request_id=?",Integer.class,user,request)).isEqualTo(1);
        code(submit(browser,UUID.randomUUID().toString(),body),202);code(submit(browser,UUID.randomUUID().toString(),body),202);code(submit(browser,UUID.randomUUID().toString(),body),429);
        code(submit(browser,request,body),202);
        var stale=new Browser();stale.cookies.putAll(browser.cookies);stale.csrf=browser.csrf;
        code(send(browser,"/api/v1/auth/logout",Map.of()),204);
        code(submit(browser,request,body),403); // Logout cleared the active browser's CSRF cookie.
        code(submit(stale,request,body),401); // The retained JWT/CSRF pair still cannot revive its DB session.
    }
    private static class Browser {final Map<String,String> cookies=new HashMap<>();String csrf;}
    private HttpResponse<String> submit(Browser b,String key,Object body)throws Exception{return send(b,"/api/v1/problems/sum-two-integers/submissions",body,key);}
    private HttpResponse<String> send(Browser b,String path,Object body)throws Exception{return send(b,path,body,null);}
    private HttpResponse<String> send(Browser b,String path,Object body,String request)throws Exception{
        var q=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(10));
        if(!b.cookies.isEmpty())q.header("Cookie",b.cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("; ")));
        if(request!=null)q.header("Idempotency-Key",request);
        if(body==null)q.GET();else q.header("Content-Type","application/json").header(path.startsWith("/api/v1/admin/")?"X-ADMIN-CSRF-TOKEN":"X-CSRF-TOKEN",b.csrf).header("Origin","http://localhost:"+port).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=http.send(q.build(),HttpResponse.BodyHandlers.ofString());
        for(String value:response.headers().allValues("Set-Cookie")){var c=HttpCookie.parse(value).getFirst();if(c.getMaxAge()==0)b.cookies.remove(c.getName());else b.cookies.put(c.getName(),c.getValue());}
        if(response.statusCode()==200&&path.contains("/auth/")&&response.body().contains("\"csrf\""))b.csrf=JsonPath.read(response.body(),"$.csrf.token");
        return response;
    }
    private String publicKey(long revision,String request){return RedisSupport.PREFIX+"public:"+revision+":"+RedisSupport.digest(request);}
    private void processOld(long revision){long id=db().queryForObject("SELECT id FROM cache_invalidation_outbox WHERE namespace='public' AND old_revision=?",Long.class,revision);invalidations.process(id);}
    private JdbcTemplate restricted(String user,String password){return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),user,password));}
    private JdbcTemplate db(){return restricted("forgeoj_migrator","m0-migrator-test-secret");}
    private JdbcTemplate dbApi(){return new JdbcTemplate(apiData);}
    /** Exact same-connection maintenance protocol; business publication is also tested via HTTP. */
    private void change(String sql,Object...args)throws Exception {
        try(var connection=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret")){
            connection.setAutoCommit(false);
            try{
                try(var statement=connection.prepareStatement(sql)){for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);statement.executeUpdate();}
                long before;
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT revision FROM cache_epoch WHERE namespace='public' FOR UPDATE")){rows.next();before=rows.getLong(1);}
                try(var statement=connection.createStatement()){statement.executeUpdate("UPDATE cache_epoch SET revision=revision+1 WHERE namespace='public'");}
                try(var statement=connection.prepareStatement("INSERT INTO cache_invalidation_outbox(namespace,old_revision) VALUES('public',?)")){statement.setLong(1,before);statement.executeUpdate();}
                connection.commit();
            }catch(Exception failure){connection.rollback();throw failure;}
        }
    }
    private HttpResponse<String> get(String path)throws Exception{return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private void code(HttpResponse<String> r,int expected){assertThat(r.statusCode()).describedAs("HTTP body %s",r.body()).isEqualTo(expected);}
}
