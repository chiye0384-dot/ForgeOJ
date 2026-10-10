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
class PublicReviewIntegrationTests {
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql")).withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret").withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",()->"forgeoj_api");r.add("spring.datasource.password",()->"m0-api-test-secret");r.add("spring.flyway.url",MYSQL::getJdbcUrl);r.add("spring.flyway.user",()->"forgeoj_migrator");r.add("spring.flyway.password",()->"m0-migrator-test-secret");}
    @LocalServerPort int port;
    @MockitoSpyBean AdminMapper mapper;
    @MockitoSpyBean com.forgeoj.api.auth.AccountMapper authors;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSessions;
    @Autowired PublicReviewMapper reviewData;

    static final String PASSWORD="public-admin-initial-fixture",NEXT="public-admin-changed-fixture",OTHER="public-admin-next-fixture";
    final HttpClient client=HttpClient.newHttpClient();
    long rootId;
    @BeforeEach void seed() throws Exception {
        // Only this disposable fixture's migrator removes its dependent search facts.
        for(String table:List.of("public_search_dead_recovery","public_search_dead_outbox","public_search_delivery","public_search_outbox","public_search_version","public_search_rebuild"))db().execute("DELETE FROM "+table);
        db().update("UPDATE public_search_control SET active_job=NULL,readable_epoch=0,index_name=NULL,index_uuid=NULL WHERE id=1");
        reset(mapper);var db=db();for(String t:List.of("judge_task_attempt","judge_task","submission"))db.execute("DELETE FROM "+t);for(String table:List.of("public_problem_feedback","public_problem_feedback_case","public_review_recheck","public_revision_draft","public_problem_governance","public_review_decision","content_review"))db.execute("DELETE FROM "+table);db.execute("DELETE FROM official_problem_solution");db.execute("UPDATE problem SET current_judge_version_id=NULL");db.execute("DELETE FROM problem_test_case");db.execute("DELETE FROM problem_judge_version");db.execute("DELETE FROM problem");for(String table:List.of("outbox_event","content_validation_attempt","content_validation_job","content_validation_test_case","content_validation_snapshot","authored_problem_test_case","authored_problem_draft"))db.execute("DELETE FROM "+table);for(String table:List.of("admin_audit_event","admin_creation_request","admin_refresh_token","admin_login_session","admin_account"))db.execute("DELETE FROM "+table);db.execute("ALTER TABLE admin_account AUTO_INCREMENT=1");db.update("UPDATE admin_policy_fence SET bootstrapped=FALSE WHERE id=1");
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret")){rootId=AdminProvisioning.bootstrap(c,"Root_admin",PASSWORD,new BCryptPasswordEncoder());}
        db.update("INSERT INTO user_account(id,username,password_hash,status) VALUES(1,'ordinary',?,'ACTIVE') ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash),status='ACTIVE'",new BCryptPasswordEncoder().encode(PASSWORD));db.update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(1)");
    }

    // These fixtures set validation terminal rows to exercise HTTP/SQL contracts;
    // they are not evidence that a Worker executed the programs.
    record Fixture(String draft,String job,String review) {}
    static final String AUTHOR="/api/v1/me/authored-problems";
    private Browser ordinary(String name)throws Exception{
        var b=new Browser();code(send(b,"GET","/api/v1/auth/session",null,null),200);
        code(send(b,"POST","/api/v1/auth/login",Map.of("username",name,"password",PASSWORD),"http://localhost:"+port),200);return b;
    }
    private HttpResponse<String> userWrite(Browser b,String method,String path,Object body)throws Exception{return send(b,method,path,body,"http://localhost:"+port);}
    private Fixture fixture(Browser owner)throws Exception{
        var created=userWrite(owner,"POST",AUTHOR,Map.of("title","审核冻结测试"));code(created,201);String draft=(String)read(created,"$.draft.id");
        var c=new LinkedHashMap<String,Object>(JsonPath.read(send(owner,"GET",AUTHOR+"/"+draft,null,null).body(),"$.content"));
        var m=new LinkedHashMap<String,Object>((Map<String,Object>)c.get("metadata"));m.put("statement","原创求和题");m.put("inputDescription","两个整数");m.put("outputDescription","和");m.put("licenseStatement","原创公开测试许可");m.put("samples",List.of(Map.of("input","1 2\n","output","3\n"),Map.of("input","2 3\n","output","5\n")));c.put("metadata",m);
        String program="import java.util.Scanner; public class Main { public static void main(String[] args) { Scanner s=new Scanner(System.in); System.out.println(s.nextLong()+s.nextLong()); }}";
        c.put("referenceCode",program+"\n// review reference marker");c.put("solutionCode",program+"\n// independent solution marker");c.put("solutionIdea","相加");
        code(userWrite(owner,"PUT",AUTHOR+"/"+draft,Map.of("expectedVersion",1,"content",c)),200);
        code(userWrite(owner,"PUT",AUTHOR+"/"+draft+"/tests",Map.of("expectedVersion",2,"tests",List.of(Map.of("input","11 22\n","expectedOutput","33\n")))),200);
        var validation=userWrite(owner,"POST",AUTHOR+"/"+draft+"/validations",Map.of("expectedVersion",3,"requestId",UUID.randomUUID().toString()));code(validation,202);String job=(String)read(validation,"$.jobId");
        db().update("UPDATE content_validation_job SET processing_status='FINISHED',validation_status='PASSED',reference_result='ACCEPTED',solution_result='ACCEPTED',finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",job);
        var review=userWrite(owner,"POST",AUTHOR+"/"+draft+"/reviews",Map.of("expectedVersion",3,"validationJobId",job,"requestId",UUID.randomUUID().toString()));code(review,202);return new Fixture(draft,job,(String)read(review,"$.reviewId"));
    }
    private Map<String,Object> decision(String reason){return Map.of("expectedVersion",0,"clientRequestId",UUID.randomUUID().toString(),"reason",reason);}
    private String approve(Browser root,Fixture f)throws Exception{var r=write(root,"POST","/content-reviews/"+f.review()+"/approve",decision("验证题面及授权"));code(r,200);return (String)read(r,"$.publishedSlug");}
    @Test void frozenReadIsScopedAndPublicationCopiesAllSamplesTestsAndSolutionOnce()throws Exception{
        var owner=ordinary("ordinary");var f=fixture(owner);var root=ready();
        var queue=get(root,"/content-reviews");code(queue,200);assertThat(queue.body()).doesNotContain("referenceCode","sourceCode","independent solution marker","11 22");
        var detail=get(root,"/content-reviews/"+f.review());code(detail,200);assertThat(detail.body()).contains("原创求和题","independent solution marker").doesNotContain("review reference marker","11 22","33\\n","inputGzip");
        var reference=get(root,"/content-reviews/"+f.review()+"/reference");code(reference,200);assertThat(reference.body()).contains("review reference marker");
        assertThat(reviewData.lockReview(f.review())).isPresent();
        code(get(root,"/content-reviews/"+UUID.randomUUID()),404);
        var body=decision("允许发布冻结内容");var published=write(root,"POST","/content-reviews/"+f.review()+"/approve",body);code(published,200);String slug=(String)read(published,"$.publishedSlug");code(write(root,"POST","/content-reviews/"+f.review()+"/approve",body),200);
        var changed=new HashMap<>(body);changed.put("reason","不同理由");code(write(root,"POST","/content-reviews/"+f.review()+"/approve",changed),409);
        var publicDetail=send(new Browser(),"GET","/api/v1/problems/"+slug,null,null);code(publicDetail,200);assertThat((List<?>)read(publicDetail,"$.publicSamples")).hasSize(2);assertThat(publicDetail.body()).contains("原创公开测试许可","ordinary").doesNotContain("review reference marker","independent solution marker","11 22");
        assertThat(db().queryForObject("SELECT COUNT(*) FROM problem",Integer.class)).isEqualTo(1);assertThat(db().queryForObject("SELECT COUNT(*) FROM problem_test_case",Integer.class)).isEqualTo(1);assertThat(db().queryForObject("SELECT COUNT(*) FROM official_problem_solution",Integer.class)).isEqualTo(1);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_audit_event WHERE action='REVIEW_REFERENCE_READ'",Integer.class)).isEqualTo(1);
        var history=send(owner,"GET",AUTHOR+"/"+f.draft()+"/reviews",null,null);code(history,200);assertThat(history.body()).contains("APPROVED","允许发布冻结内容",slug);
    }
    @Test void rejectUnlocksDraftAndResubmissionKeepsOldReasonAndFrozenHistory()throws Exception{
        var owner=ordinary("ordinary");var f=fixture(owner);var root=ready();code(write(root,"POST","/content-reviews/"+f.review()+"/reject",decision("题面有歧义，请修改")),200);
        var detail=send(owner,"GET",AUTHOR+"/"+f.draft(),null,null);code(detail,200);assertThat(detail.body()).contains("\"status\":\"DRAFT\"");
        var c=new LinkedHashMap<String,Object>(JsonPath.read(detail.body(),"$.content"));c.put("solutionIdea","明确相加");code(userWrite(owner,"PUT",AUTHOR+"/"+f.draft(),Map.of("expectedVersion",3,"content",c)),200);
        code(userWrite(owner,"POST",AUTHOR+"/"+f.draft()+"/reviews",Map.of("expectedVersion",4,"validationJobId",f.job(),"requestId",UUID.randomUUID().toString())),409);
        var history=send(owner,"GET",AUTHOR+"/"+f.draft()+"/reviews/"+f.review(),null,null);code(history,200);assertThat(history.body()).contains("REJECTED","题面有歧义，请修改","相加").doesNotContain("明确相加");
        code(write(root,"POST","/content-reviews/"+f.review()+"/approve",decision("过时审批")),409);assertThat(db().queryForObject("SELECT COUNT(*) FROM problem",Integer.class)).isZero();
    }
    @Test void lastRecheckMustPassAndUsesTheSameFrozenSnapshotAndOneOutbox()throws Exception{
        var owner=ordinary("ordinary");var f=fixture(owner);var root=ready();var body=decision("重新验证冻结版本");String path="/content-reviews/"+f.review();var rerun=write(root,"POST",path+"/recheck",body);code(rerun,202);String job=(String)read(rerun,"$.jobId");code(write(root,"POST",path+"/recheck",body),202);
        var altered=new HashMap<>(body);altered.put("reason","不同重验理由");code(write(root,"POST",path+"/recheck",altered),409);
        assertThat(job).isNotEqualTo(f.job());assertThat(db().queryForObject("SELECT snapshot_id FROM content_validation_job WHERE id=?",String.class,job)).isEqualTo(db().queryForObject("SELECT snapshot_id FROM content_validation_job WHERE id=?",String.class,f.job()));
        assertThat(db().queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,job)).isEqualTo(1);code(write(root,"POST",path+"/approve",decision("不能借旧 PASSED 发布")),409);
        db().update("UPDATE content_validation_job SET processing_status='FINISHED',validation_status='FAILED',reference_result='ACCEPTED',solution_result='WRONG_ANSWER',finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",job);code(write(root,"POST",path+"/approve",decision("失败不能发布")),409);
        db().update("UPDATE content_validation_job SET validation_status='PASSED',solution_result='ACCEPTED' WHERE id=?",job);approve(root,f);
    }
    @Test void approvalWithdrawalRaceLeavesOneTerminalStateAndNeverPartialPublication()throws Exception{
        var owner=ordinary("ordinary");var f=fixture(owner);var root=ready();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return write(root,"POST","/content-reviews/"+f.review()+"/approve",decision("并发审批")).statusCode();});
            var b=pool.submit(()->{start.await();return userWrite(owner,"POST",AUTHOR+"/"+f.draft()+"/reviews/"+f.review()+"/withdraw",Map.of("expectedVersion",3,"expectedReviewVersion",0)).statusCode();});start.countDown();assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        String status=db().queryForObject("SELECT review_status FROM content_review WHERE id=?",String.class,f.review());int expected=status.equals("APPROVED")?1:0;assertThat(db().queryForObject("SELECT COUNT(*) FROM problem",Integer.class)).isEqualTo(expected);assertThat(db().queryForObject("SELECT COUNT(*) FROM public_review_decision",Integer.class)).isEqualTo(expected);
    }
    @Test void auditFailureRollsBackPublicationAndWorkersCannotGovernOrReadFeedback()throws Exception{
        var owner=ordinary("ordinary");var f=fixture(owner);var root=ready();doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected audit failure")).when(mapper).audit(any());
        try{code(write(root,"POST","/content-reviews/"+f.review()+"/approve",decision("回滚测试")),503);verify(mapper,atLeastOnce()).audit(any());}finally{reset(mapper);}
        assertThat(db().queryForObject("SELECT COUNT(*) FROM problem",Integer.class)).isZero();assertThat(db().queryForObject("SELECT review_status FROM content_review WHERE id=?",String.class,f.review())).isEqualTo("PENDING");
        var worker=restricted("forgeoj_worker","m0-worker-test-secret");var api=restricted("forgeoj_api","m0-api-test-secret");
        for(String table:List.of("public_problem_governance","public_revision_draft","public_review_decision","public_review_recheck","public_problem_feedback_case","public_problem_feedback"))assertThatThrownBy(()->worker.queryForList("SELECT * FROM "+table)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        for(String table:List.of("public_review_decision","public_review_recheck","public_problem_feedback")){assertThatThrownBy(()->api.update("DELETE FROM "+table)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->api.update("UPDATE "+table+" SET client_request_id=client_request_id WHERE 1=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    }
    @Test void feedbackMergesUsersButKeepsOwnHistoryPrivateAndClosedCasesImmutable()throws Exception{
        var owner=ordinary("ordinary");var f=fixture(owner);var root=ready();String slug=approve(root,f),path="/api/v1/problems/"+slug+"/feedbacks";
        db().update("INSERT INTO user_account(id,username,password_hash,status) SELECT 2,'second_user',password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'");db().update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(2)");var other=ordinary("second_user");
        var first=Map.of("clientRequestId",UUID.randomUUID().toString(),"category","AMBIGUITY","body","本人描述歧义");var response=userWrite(owner,"POST",path,first);code(response,201);String id=(String)read(response,"$.caseId");code(userWrite(owner,"POST",path,first),201);code(userWrite(other,"POST",path,Map.of("clientRequestId",UUID.randomUUID().toString(),"category","TEST_ERROR","body","他人私密反馈")),201);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM public_problem_feedback_case",Integer.class)).isEqualTo(1);assertThat(db().queryForObject("SELECT COUNT(*) FROM public_problem_feedback",Integer.class)).isEqualTo(2);
        var mine=send(owner,"GET",path,null,null);code(mine,200);assertThat(mine.body()).contains("本人描述歧义").doesNotContain("他人私密反馈");code(send(new Browser(),"GET",path,null,null),401);
        code(userWrite(owner,"POST",path,Map.of("clientRequestId",UUID.randomUUID().toString(),"category","OTHER","body","不能替换同一案件反馈")),409);
        code(get(root,"/feedback-cases/"+id),200);code(write(root,"POST","/feedback-cases/"+id+"/close",Map.of("expectedVersion",1,"reason","已检查，保留原题")),200);code(write(root,"POST","/feedback-cases/"+id+"/close",Map.of("expectedVersion",1,"reason","过时处理")),409);
        var replay=userWrite(owner,"POST",path,first);code(replay,201);assertThat(read(replay,"$.caseStatus")).isEqualTo("CLOSED");code(userWrite(owner,"POST",path,Map.of("clientRequestId",UUID.randomUUID().toString(),"category","OTHER","body","新的问题")),201);assertThat(db().queryForObject("SELECT COUNT(*) FROM public_problem_feedback_case",Integer.class)).isEqualTo(2);
    }
    @Test void archivedAndInvalidProblemsPreserveFactsButOnlyOrdinaryArchiveCanRestore()throws Exception{
        var owner=ordinary("ordinary");var f=fixture(owner);var root=ready();String slug=approve(root,f);long id=db().queryForObject("SELECT id FROM problem WHERE slug=?",Long.class,slug);String path="/public-problems/"+id;
        code(write(root,"POST",path+"/archive",Map.of("expectedVersion",1,"reason","暂时下架")),200);code(send(new Browser(),"GET","/api/v1/problems/"+slug,null,null),404);
        code(write(root,"POST",path+"/restore",Map.of("expectedVersion",1,"reason","过时恢复")),409);code(write(root,"POST",path+"/restore",Map.of("expectedVersion",2,"reason","允许恢复")),200);
        code(write(root,"POST",path+"/invalidate",Map.of("expectedVersion",3,"reason","严重测试错误")),200);code(write(root,"POST",path+"/restore",Map.of("expectedVersion",4,"reason","不能复活旧判题依据")),409);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM problem_test_case",Integer.class)).isEqualTo(1);assertThat(db().queryForObject("SELECT COUNT(*) FROM content_validation_snapshot",Integer.class)).isEqualTo(1);assertThat(db().queryForObject("SELECT data_invalid FROM public_problem_governance WHERE problem_id=?",Boolean.class,id)).isTrue();
    }
    @Test void everyContentRouteRejectsOpsOrdinaryAndCurrentRoleDemotion()throws Exception{
        var user=ordinary("ordinary");var f=fixture(user);var root=ready();String slug=approve(root,f);long problem=db().queryForObject("SELECT id FROM problem WHERE slug=?",Long.class,slug);
        var response=userWrite(user,"POST","/api/v1/problems/"+slug+"/feedbacks",Map.of("clientRequestId",UUID.randomUUID().toString(),"category","OTHER","body","权限上下文"));code(response,201);String feedback=(String)read(response,"$.caseId");
        long reviewerId=create(root,"current_reviewer","CONTENT_REVIEWER");create(root,"current_ops","OPS_ADMIN");
        var reviewer=login("current_reviewer",PASSWORD);code(write(reviewer,"POST","/auth/password/change",Map.of("currentPassword",PASSWORD,"password",NEXT)),204);reviewer=login("current_reviewer",NEXT);code(get(reviewer,"/content-reviews/"+f.review()),200);
        var ops=login("current_ops",PASSWORD);code(write(ops,"POST","/auth/password/change",Map.of("currentPassword",PASSWORD,"password",NEXT)),204);ops=login("current_ops",NEXT);
        db().update("UPDATE admin_account SET role='OPS_ADMIN' WHERE id=?",reviewerId);
        for(var denied:List.of(ops,user,reviewer)){
            for(String path:List.of("/content-reviews","/content-reviews/"+f.review(),"/content-reviews/"+f.review()+"/reference","/public-problems","/feedback-cases","/feedback-cases/"+feedback))code(get(denied,path),403);
            for(String action:List.of("approve","reject","recheck"))code(write(denied,"POST","/content-reviews/"+f.review()+"/"+action,decision("越权应拒绝")),403);
            for(String action:List.of("archive","restore","invalidate"))code(write(denied,"POST","/public-problems/"+problem+"/"+action,Map.of("expectedVersion",1,"reason","越权应拒绝")),403);
            code(write(denied,"POST","/feedback-cases/"+feedback+"/close",Map.of("expectedVersion",1,"reason","越权应拒绝")),403);
        }
        assertThat(db().queryForObject("SELECT status FROM problem WHERE id=?",String.class,problem)).isEqualTo("ACTIVE");assertThat(db().queryForObject("SELECT status FROM public_problem_feedback_case WHERE id=?",String.class,feedback)).isEqualTo("OPEN");
    }
    @Test void approvalLocksAuthorBeforeQuotaAndThenWithdrawCannotWinLate()throws Exception{
        var user=ordinary("ordinary");var f=fixture(user);var root=ready();var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        doAnswer(call->{var account=sqlSessions.getMapper(com.forgeoj.api.auth.AccountMapper.class).lockAccount(1L);var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();if(authentication.getPrincipal() instanceof AdminPrincipal){entered.countDown();if(!proceed.await(15,TimeUnit.SECONDS))throw new AssertionError("author lock timeout");}return account;}).when(authors).lockAccount(1L);
        try(var pool=Executors.newFixedThreadPool(2);var observer=DriverManager.getConnection(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret")){
            observer.setAutoCommit(false);var approval=pool.submit(()->write(root,"POST","/content-reviews/"+f.review()+"/approve",decision("确认共享锁序")));
            try{
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();try(var q=observer.createStatement();var rows=q.executeQuery("SELECT user_id FROM user_judge_quota_lock WHERE user_id=1 FOR UPDATE NOWAIT")){assertThat(rows.next()).isTrue();}observer.commit();
                var withdrawal=pool.submit(()->userWrite(user,"POST",AUTHOR+"/"+f.draft()+"/reviews/"+f.review()+"/withdraw",Map.of("expectedVersion",3,"expectedReviewVersion",0)));
                proceed.countDown();code(approval.get(20,TimeUnit.SECONDS),200);code(withdrawal.get(20,TimeUnit.SECONDS),409);
            }finally{proceed.countDown();observer.rollback();}
        }finally{reset(authors);}
        assertThat(db().queryForObject("SELECT COUNT(*) FROM problem",Integer.class)).isEqualTo(1);
    }
    @Test void omittedFractionalAndExtraneousVersionsAreRejectedBeforeDisposition()throws Exception{
        var user=ordinary("ordinary");var f=fixture(user);var root=ready();String path="/content-reviews/"+f.review()+"/reject";
        var body=new HashMap<String,Object>(decision("负向请求"));body.remove("expectedVersion");code(write(root,"POST",path,body),400);body.put("expectedVersion",0.5);code(write(root,"POST",path,body),400);body.put("expectedVersion",0);body.put("grant","SUPER_ADMIN");code(write(root,"POST",path,body),400);
        assertThat(db().queryForObject("SELECT review_status FROM content_review WHERE id=?",String.class,f.review())).isEqualTo("PENDING");
    }
    @Test void originalAuthorCopyIsIdempotentPrivateAndNeverInheritsPassedValidation()throws Exception{
        var owner=ordinary("ordinary");var root=ready();String slug=approve(root,fixture(owner));String path="/api/v1/me/public-problems/"+slug+"/revisions";
        var body=revision(1,"TEXT");var copied=userWrite(owner,"POST",path,body);code(copied,201);String id=(String)read(copied,"$.draft.id");
        code(userWrite(owner,"POST",path,body),201);assertThat(read(userWrite(owner,"POST",path,body),"$.draft.id")).isEqualTo(id);
        assertThat(read(copied,"$.draft.version")).isEqualTo(1);assertThat(read(copied,"$.draft.testCount")).isEqualTo(1);assertThat(copied.body()).contains("review reference marker","independent solution marker");
        assertThat(db().queryForObject("SELECT COUNT(*) FROM content_validation_snapshot WHERE draft_id=?",Integer.class,id)).isZero();
        code(send(owner,"DELETE",AUTHOR+"/"+id+"?expectedVersion=1",Map.of(),"http://localhost:"+port),409);
        code(userWrite(owner,"POST",AUTHOR+"/"+id+"/reviews",Map.of("expectedVersion",1,"validationJobId",UUID.randomUUID().toString(),"requestId",UUID.randomUUID().toString())),409);
        var altered=new HashMap<String,Object>(body);altered.put("revisionKind","CORRECTION");code(userWrite(owner,"POST",path,altered),409);
        code(userWrite(owner,"POST",path,revision(2,"TEXT")),409);code(userWrite(owner,"POST",path,Map.of("expectedVersion",1.5,"revisionKind","TEXT","clientRequestId",UUID.randomUUID().toString())),400);
        db().update("INSERT INTO user_account(id,username,password_hash,status) SELECT 2,'second_user',password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'");db().update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(2)");var other=ordinary("second_user");
        var list=send(other,"GET","/api/v1/me/public-problems",null,null);code(list,200);assertThat(read(list,"$.total")).isEqualTo(0);
        code(userWrite(other,"POST",path,revision(1,"TEXT")),404);code(send(other,"GET",AUTHOR+"/"+id,null,null),404);code(send(root,"GET","/api/v1/auth/session",null,null),200);code(userWrite(root,"POST",path,revision(1,"TEXT")),401);
        assertThatThrownBy(()->db().update("INSERT INTO public_revision_draft(draft_id,problem_id,owner_id,client_request_id,expected_version,revision_kind) VALUES(?,(SELECT id FROM problem WHERE slug=?),2,?,1,'TEXT')",fixture(owner).draft(),slug,UUID.randomUUID().toString())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void textRevisionKeepsJudgeButChangedBasisAndStalePublicVersionCannotPublish()throws Exception{
        var owner=ordinary("ordinary");var root=ready();String slug=approve(root,fixture(owner));long problem=db().queryForObject("SELECT id FROM problem WHERE slug=?",Long.class,slug);long judge=db().queryForObject("SELECT current_judge_version_id FROM problem WHERE id=?",Long.class,problem);
        var r=copyAndReview(owner,slug,1,"TEXT","修订文字",null);assertThat(approve(root,r)).isEqualTo(slug);
        assertThat(db().queryForObject("SELECT current_judge_version_id FROM problem WHERE id=?",Long.class,problem)).isEqualTo(judge);assertThat(db().queryForObject("SELECT COUNT(*) FROM problem",Integer.class)).isEqualTo(1);assertThat(db().queryForObject("SELECT statement_text FROM problem WHERE id=?",String.class,problem)).isEqualTo("修订文字");
        var changed=copyAndReview(owner,slug,2,"TEXT","第二次修订","新的输入规则");code(write(root,"POST","/content-reviews/"+changed.review()+"/approve",decision("输入规则变化不能替换旧依据")),409);
        assertThat(db().queryForObject("SELECT review_status FROM content_review WHERE id=?",String.class,changed.review())).isEqualTo("PENDING");
        code(write(root,"POST","/content-reviews/"+changed.review()+"/reject",decision("请改为关联新题")),200);
        var stale=copyAndReview(owner,slug,2,"TEXT","过期版本",null);code(write(root,"POST","/public-problems/"+problem+"/archive",Map.of("expectedVersion",2,"reason","并发治理")),200);
        code(write(root,"POST","/content-reviews/"+stale.review()+"/approve",decision("过期发布不得覆盖")),409);assertThat(db().queryForObject("SELECT version FROM public_problem_governance WHERE problem_id=?",Long.class,problem)).isEqualTo(3);
        assertThat(db().queryForObject("SELECT statement_text FROM problem WHERE id=?",String.class,problem)).isEqualTo("修订文字");
    }
    @Test void linkedCorrectionKeepsInvalidOldFactsAndDoesNotGrantOldAcOnNewProblem()throws Exception{
        var owner=ordinary("ordinary");var root=ready();String slug=approve(root,fixture(owner));long problem=db().queryForObject("SELECT id FROM problem WHERE slug=?",Long.class,slug);
        var submission=userWriteWithRequest(owner,"/api/v1/problems/"+slug+"/submissions",Map.of("language","JAVA_21","sourceCode","public class Main {}"));code(submission,202);String sid=(String)read(submission,"$.submissionId");
        db().update("UPDATE submission SET processing_status='FINISHED',verdict='AC',finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",sid);var facts=db().queryForMap("SELECT * FROM submission WHERE id=?",sid);
        code(write(root,"POST","/public-problems/"+problem+"/invalidate",Map.of("expectedVersion",1,"reason","错误测试数据")),200);
        code(userWrite(owner,"POST","/api/v1/me/public-problems/"+slug+"/revisions",revision(2,"TEXT")),409);
        var r=copyAndReview(owner,slug,2,"CORRECTION","关联修正版",null);String next=approve(root,r);assertThat(next).isNotEqualTo(slug);
        assertThat(db().queryForMap("SELECT * FROM submission WHERE id=?",sid)).isEqualTo(facts);assertThat(db().queryForObject("SELECT data_invalid FROM public_problem_governance WHERE problem_id=?",Boolean.class,problem)).isTrue();
        assertThat(db().queryForObject("SELECT correction_of_id FROM public_problem_governance WHERE problem_id=(SELECT id FROM problem WHERE slug=?)",Long.class,next)).isEqualTo(problem);
        var solution=send(owner,"GET","/api/v1/me/problems/"+next+"/solution",null,null);code(solution,200);assertThat(read(solution,"$.access")).isEqualTo("LOCKED");assertThat(solution.body()).doesNotContain("independent solution marker");
        code(write(root,"POST","/public-problems/"+problem+"/restore",Map.of("expectedVersion",3,"reason","旧作废题不能恢复")),409);
    }
    @Test void linkedNewVersionArchivesValidOldBasisWithoutInventingInvalidation()throws Exception{
        var owner=ordinary("ordinary");var root=ready();String slug=approve(root,fixture(owner));var r=copyAndReview(owner,slug,1,"CORRECTION","新规则","修订输入规则");String next=approve(root,r);assertThat(next).isNotEqualTo(slug);
        var old=db().queryForMap("SELECT p.status,g.data_invalid,g.version FROM problem p JOIN public_problem_governance g ON p.id=g.problem_id WHERE p.slug=?",slug);assertThat(old.get("status")).isEqualTo("ARCHIVED");assertThat(old.get("data_invalid")).isEqualTo(false);assertThat(((Number)old.get("version")).longValue()).isEqualTo(2);
    }
    @Test void simultaneousFeedbacksMergeOneCaseWithoutAutomaticArchival()throws Exception{
        var owner=ordinary("ordinary");var root=ready();String slug=approve(root,fixture(owner));db().update("INSERT INTO user_account(id,username,password_hash,status) SELECT 2,'second_user',password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'");db().update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(2)");var other=ordinary("second_user");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return userWrite(owner,"POST","/api/v1/problems/"+slug+"/feedbacks",Map.of("clientRequestId",UUID.randomUUID().toString(),"category","OTHER","body","并发反馈一"));});
            var b=pool.submit(()->{start.await();return userWrite(other,"POST","/api/v1/problems/"+slug+"/feedbacks",Map.of("clientRequestId",UUID.randomUUID().toString(),"category","OTHER","body","并发反馈二"));});start.countDown();var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);code(first,201);code(second,201);assertThat(read(first,"$.caseId")).isEqualTo(read(second,"$.caseId"));
        }
        assertThat(db().queryForObject("SELECT COUNT(*) FROM public_problem_feedback_case",Integer.class)).isEqualTo(1);assertThat(db().queryForObject("SELECT COUNT(*) FROM public_problem_feedback",Integer.class)).isEqualTo(2);assertThat(db().queryForObject("SELECT status FROM problem WHERE slug=?",String.class,slug)).isEqualTo("ACTIVE");
    }
    private Map<String,Object> revision(long version,String kind){return Map.of("expectedVersion",version,"revisionKind",kind,"clientRequestId",UUID.randomUUID().toString());}
    private Fixture copyAndReview(Browser owner,String slug,long version,String kind,String statement,String input)throws Exception{
        var copied=userWrite(owner,"POST","/api/v1/me/public-problems/"+slug+"/revisions",revision(version,kind));code(copied,201);String draft=(String)read(copied,"$.draft.id");
        var c=new LinkedHashMap<String,Object>((Map<String,Object>)read(copied,"$.content"));var metadata=new LinkedHashMap<String,Object>((Map<String,Object>)c.get("metadata"));metadata.put("statement",statement);if(input!=null)metadata.put("inputDescription",input);c.put("metadata",metadata);
        code(userWrite(owner,"PUT",AUTHOR+"/"+draft,Map.of("expectedVersion",1,"content",c)),200);
        var validation=userWrite(owner,"POST",AUTHOR+"/"+draft+"/validations",Map.of("expectedVersion",2,"requestId",UUID.randomUUID().toString()));code(validation,202);String job=(String)read(validation,"$.jobId");
        db().update("UPDATE content_validation_job SET processing_status='FINISHED',validation_status='PASSED',reference_result='ACCEPTED',solution_result='ACCEPTED',finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",job);
        var review=userWrite(owner,"POST",AUTHOR+"/"+draft+"/reviews",Map.of("expectedVersion",2,"validationJobId",job,"requestId",UUID.randomUUID().toString()));code(review,202);return new Fixture(draft,job,(String)read(review,"$.reviewId"));
    }
    private HttpResponse<String> userWriteWithRequest(Browser b,String path,Object body)throws Exception{
        var q=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Cookie",b.cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("; "))).header("Content-Type","application/json").header("X-CSRF-TOKEN",b.csrf).header("Origin","http://localhost:"+port).header("Idempotency-Key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body)));
        return client.send(q.build(),HttpResponse.BodyHandlers.ofString());
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
        if(body!=null){q.header("Content-Type","application/json").header(path.startsWith("/api/v1/admin/")?"X-ADMIN-CSRF-TOKEN":"X-CSRF-TOKEN",b.csrf==null?"":b.csrf);if(origin!=null)q.header("Origin",origin);q.method(method,HttpRequest.BodyPublishers.ofString(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body)));}else q.GET();
        var result=client.send(q.build(),HttpResponse.BodyHandlers.ofString());for(String value:result.headers().allValues("Set-Cookie")){var c=java.net.HttpCookie.parse(value).getFirst();if(c.getMaxAge()==0)b.cookies.remove(c.getName());else b.cookies.put(c.getName(),c.getValue());}
        if(result.statusCode()==200&&path.contains("/auth/")&&result.body().contains("\"csrf\"")){Object csrfToken=JsonPath.read(result.body(),"$.csrf.token");b.csrf=(String)csrfToken;}return result;
    }
    private Object read(HttpResponse<String> r,String path){return JsonPath.read(r.body(),path);}
    private void code(HttpResponse<String> r,int code){assertThat(r.statusCode()).describedAs("HTTP body %s",r.body()).isEqualTo(code);}
    private void assertAnonymous(Browser b)throws Exception{assertThat(read(get(b,"/auth/session"),"$.authenticated")).isEqualTo(false);}
    private static final class Browser {final Map<String,String> cookies=new HashMap<>();String csrf;Browser copy(){var b=new Browser();b.cookies.putAll(cookies);b.csrf=csrf;return b;}}
}
