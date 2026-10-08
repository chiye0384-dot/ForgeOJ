/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.flyway.locations=classpath:db/migration,classpath:db/devdata","forgeoj.auth.limits.multiplier=100","forgeoj.assignments.scheduler.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false","spring.rabbitmq.listener.direct.auto-startup=false"})
class AssignmentIntegrationTests {
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql")).withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret").withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",()->"forgeoj_api");r.add("spring.datasource.password",()->"m0-api-test-secret");r.add("spring.flyway.url",MYSQL::getJdbcUrl);r.add("spring.flyway.user",()->"forgeoj_migrator");r.add("spring.flyway.password",()->"m0-migrator-test-secret");}
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();
    static final String SLUG="sum-two-integers",CODE="import java.util.Scanner; public class Main {public static void main(String[] a){Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}";
    Browser owner,student,late; static final java.util.concurrent.atomic.AtomicInteger USER_IDS=new java.util.concurrent.atomic.AtomicInteger(100); int studentId,lateId;
    @MockitoSpyBean AssignmentMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired AssignmentLifecycle lifecycle;
    @BeforeEach void seed() throws Exception {
        reset(mapper);db().update("UPDATE submission SET processing_status='CANCELLED',status_version=status_version+1,finished_at=CURRENT_TIMESTAMP(6) WHERE processing_status='QUEUED'");
        db().update("UPDATE self_test_job SET processing_status='CANCELLED',status_version=status_version+1,finished_at=CURRENT_TIMESTAMP(6),expires_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY) WHERE processing_status='QUEUED'");
        db().update("UPDATE user_account SET status='ACTIVE' WHERE id=1");
        studentId=USER_IDS.incrementAndGet();lateId=USER_IDS.incrementAndGet();for(int i=studentId;i<=lateId;i++) {db().update("INSERT INTO user_account(id,username,password_hash,status) SELECT ?,?,password_hash,'ACTIVE' FROM user_account WHERE id=1 ON DUPLICATE KEY UPDATE status='ACTIVE'",i,"assignment-fixture-"+i);db().update("INSERT IGNORE INTO user_judge_quota_lock(user_id) VALUES(?)",i);}
        db().update("INSERT IGNORE INTO official_problem_solution(judge_version_id,idea,language,source_code) VALUES(1,'original isolated solution','JAVA_21','original independent solution sentinel')");
        db().update("UPDATE problem SET current_judge_version_id=1,status='ACTIVE',statement_text='读入两个有符号整数，输出它们的和。' WHERE id=1");
        owner=login("learner");student=login("assignment-fixture-"+studentId);late=login("assignment-fixture-"+lateId);
    }
    @Test void strictDraftCasRolesAndCreationReplay() throws Exception {
        String room=room();join(student,room);var d=definition(false,true,"AFTER_AC",future(120));String key=UUID.randomUUID().toString();
        var created=create(room,key,d);code(created,201);String id=read(created,"$.assignment.id");code(create(room,key,d),201);var changed=new LinkedHashMap<>(d);changed.put("title","different");code(create(room,key,changed),409);
        var aliases=new LinkedHashMap<>(d);aliases.put("problemSlugs",List.of(SLUG,SLUG.toUpperCase(Locale.ROOT)));code(create(room,UUID.randomUUID().toString(),aliases),400);
        code(get(student,path(room,id)),404);code(write(student,"POST",path(room,null),Map.of("clientRequestId",UUID.randomUUID().toString(),"definition",d)),403);
        code(edit(room,id,1,changed),200);code(edit(room,id,1,d),409);code(create(room,key,d),201);
        var foreign=room();code(get(owner,path(foreign,id)),404);code(write(owner,"POST",path(room,id)+"/publish",Map.of("expectedVersion",2,"unrecognized",true)),400);
        code(write(owner,"DELETE",path(room,id),null),405);
        code(write(owner,"DELETE","/api/v1/classrooms/"+room+"?expectedVersion="+roomVersion(room),null),409);
        assertThatThrownBy(()->db().update("DELETE FROM classroom WHERE id=?",room)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void scheduledStartSnapshotsOnlyCurrentMembersAndAddsLateMemberExplicitly() throws Exception {
        String room=room();join(student,room);String id=id(create(room,UUID.randomUUID().toString(),definition(false,true,"AFTER_AC",future(120))));
        code(publish(room,id,1,future(2)),200);assertThat((Object) read(get(student,path(room,id)),"$.participating")).isEqualTo(false);
        Thread.sleep(2300);try(var executor=Executors.newFixedThreadPool(2)){var a=executor.submit(()->lifecycle.refresh(room));var b=executor.submit(()->lifecycle.refresh(room));a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);}
        assertThat(db().queryForObject("SELECT COUNT(*) FROM assignment_participant WHERE assignment_id=?",Integer.class,id)).isEqualTo(2);
        join(late,room);code(get(late,path(room,id)),404);code(submit(late,room,id,SLUG,UUID.randomUUID().toString()),404);
        code(write(owner,"POST",path(room,id)+"/participants",Map.of("expectedVersion",assignmentVersion(id),"userId",lateId)),200);
        assertThat((Object) read(get(late,path(room,id)),"$.participating")).isEqualTo(true);assertThat(db().queryForObject("SELECT COUNT(*) FROM assignment_participant WHERE assignment_id=?",Integer.class,id)).isEqualTo(3);
    }
    @Test void precompletedIsRealOwnerVersionProofAndDoesNotCreateFakeAttemptOrTeacherAccess() throws Exception {
        String room=room();join(student,room);String old=publicSubmission(student);finish(old,"AC");long total=count("submission");
        String id=id(create(room,UUID.randomUUID().toString(),definition(true,true,"AFTER_AC",future(120))));code(publish(room,id,1,null),200);
        var detail=get(student,path(room,id));assertThat((Object) read(detail,"$.problems[0].grade.state")).isEqualTo("PRECOMPLETED");assertThat((Object) read(detail,"$.problems[0].grade.attempts")).isEqualTo(0);assertThat((Object) read(detail,"$.problems[0].grade.completionSubmissionId")).isEqualTo(old);
        assertThat(count("submission")).isEqualTo(total);assertThat(db().queryForObject("SELECT COUNT(*) FROM assignment_attempt WHERE assignment_id=?",Integer.class,id)).isZero();
        code(get(owner,"/api/v1/submissions/"+old),404);assertThat(get(owner,path(room,id)).body()).doesNotContain(old,"sourceCode");
        String no=id(create(room,UUID.randomUUID().toString(),definition(false,true,"AFTER_AC",future(120))));code(publish(room,no,1,null),200);assertThat((Object) read(get(student,path(room,no)),"$.problems[0].grade.state")).isEqualTo("NOT_STARTED");
    }
    @Test void formalReplayLinksExactlyOneSnapshotAndHalfwayFailureRollsBack() throws Exception {
        String room=room();join(student,room);String id=active(room,false,true,"AFTER_AC",future(120)),key=UUID.randomUUID().toString();
        try(var executor=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);var a=executor.submit(()->{start.await();return submit(student,room,id,SLUG,key);});var b=executor.submit(()->{start.await();return submit(student,room,id,SLUG,key);});start.countDown();code(a.get(15,TimeUnit.SECONDS),202);code(b.get(15,TimeUnit.SECONDS),202);}
        assertThat(db().queryForObject("SELECT COUNT(*) FROM assignment_attempt WHERE assignment_id=?",Integer.class,id)).isEqualTo(1);
        String another=active(room,false,true,"AFTER_AC",future(120));code(submit(student,room,another,SLUG,key),409);
        long before=count("submission"),tasks=count("judge_task"),events=count("outbox_event");doThrow(new IllegalStateException("isolated assignment rollback")).when(mapper).attempt(anyString(),anyString(),anyLong(),anyLong(),anyLong(),any());
        code(submit(student,room,id,SLUG,UUID.randomUUID().toString()),503);reset(mapper);assertThat(count("submission")).isEqualTo(before);assertThat(count("judge_task")).isEqualTo(tasks);assertThat(count("outbox_event")).isEqualTo(events);
    }
    @Test void acceptedBeforeDeadlineAcFinishedAfterDeadlineIsOnTimeAndHardCutoffRejectsNew() throws Exception {
        String room=room();join(student,room);String id=active(room,false,false,"AFTER_AC",future(3)),key=UUID.randomUUID().toString();var queued=submit(student,room,id,SLUG,key);code(queued,202);String submission=read(queued,"$.submissionId");
        Thread.sleep(3200);finish(submission,"AC");var detail=get(student,path(room,id));assertThat((Object) read(detail,"$.assignment.status")).isEqualTo("ENDED");assertThat((Object) read(detail,"$.problems[0].grade.state")).isEqualTo("ON_TIME_AC");
        code(submit(student,room,id,SLUG,key),202);assertThat(db().queryForObject("SELECT COUNT(*) FROM assignment_attempt WHERE assignment_id=?",Integer.class,id)).isEqualTo(1);
        code(submit(student,room,id,SLUG,UUID.randomUUID().toString()),409);assertThat(db().queryForObject("SELECT s.finished_at>a.deadline_at FROM submission s JOIN assignment_attempt t ON t.submission_id=s.id JOIN classroom_assignment a ON a.id=t.assignment_id WHERE s.id=?",Boolean.class,submission)).isTrue();
    }
    @Test void lateAcUpgradesAfterExtensionAndLaterFailedAttemptNeverErasesCompletion() throws Exception {
        String room=room();join(student,room);String deadline=future(2),id=active(room,false,true,"AFTER_AC",deadline);Thread.sleep(2300);
        var r=submit(student,room,id,SLUG,UUID.randomUUID().toString());code(r,202);String accepted=read(r,"$.submissionId");finish(accepted,"AC");assertThat((Object) read(get(student,path(room,id)),"$.problems[0].grade.state")).isEqualTo("LATE_AC");
        code(edit(room,id,assignmentVersion(id),definition(false,true,"AFTER_AC",future(60))),200);assertThat((Object) read(get(student,path(room,id)),"$.problems[0].grade.state")).isEqualTo("ON_TIME_AC");
        code(edit(room,id,assignmentVersion(id),definition(false,true,"AFTER_AC",deadline)),409);code(edit(room,id,assignmentVersion(id),definition(true,true,"AFTER_AC",future(120))),409);
        var failed=submit(student,room,id,SLUG,UUID.randomUUID().toString());code(failed,202);finish(read(failed,"$.submissionId"),"WA");assertThat((Object) read(get(student,path(room,id)),"$.problems[0].grade.state")).isEqualTo("ON_TIME_AC");assertThat((Object) read(get(student,path(room,id)),"$.problems[0].grade.attempts")).isEqualTo(2);
    }
    @Test void publicFreePracticeEarlyRecordAndUnrelatedAcCannotBypassAllAssignmentPolicies() throws Exception {
        String room=room();join(student,room);code(write(student,"POST","/api/v1/me/problems/"+SLUG+"/solution/early-view",Map.of("judgeVersion",1,"confirmEarlyView",true)),200);
        String id=active(room,false,true,"AFTER_AC",future(120));String unrelated=publicSubmission(student);finish(unrelated,"AC");
        assertThat((Object) read(get(student,"/api/v1/me/problems/"+SLUG+"/solution"),"$.access")).isEqualTo("LOCKED");code(write(student,"POST","/api/v1/me/problems/"+SLUG+"/solution/early-view",Map.of("judgeVersion",1,"confirmEarlyView",true)),409);
        assertThat((Object)read(get(student,"/api/v1/me/problems/"+SLUG.toUpperCase(Locale.ROOT)+"/solution"),"$.access")).isEqualTo("LOCKED");
        code(write(student,"POST","/api/v1/me/problems/"+SLUG.toUpperCase(Locale.ROOT)+"/solution/early-view",Map.of("judgeVersion",1,"confirmEarlyView",true)),409);
        var own=submit(student,room,id,SLUG,UUID.randomUUID().toString());code(own,202);finish(read(own,"$.submissionId"),"AC");assertThat((Object) read(get(student,path(room,id)+"/problems/"+SLUG+"/solution"),"$.access")).isEqualTo("AC");
        String restrictive=active(room,false,true,"AFTER_DEADLINE",future(120));assertThat((Object) read(get(student,path(room,id)+"/problems/"+SLUG+"/solution"),"$.access")).isEqualTo("LOCKED");code(write(owner,"POST",path(room,restrictive)+"/cancel",Map.of("expectedVersion",assignmentVersion(restrictive),"reason","original isolated cancellation")),200);assertThat((Object) read(get(student,path(room,id)+"/problems/"+SLUG+"/solution"),"$.access")).isEqualTo("AC");
    }
    @Test void exitKeepsOnlyOwnSummaryAndRejoinPreservesOriginalParticipant() throws Exception {
        String room=room();join(student,room);String invite=invite(room),id=active(room,false,true,"AFTER_AC",future(120));var queued=submit(student,room,id,SLUG,UUID.randomUUID().toString());code(queued,202);
        code(roomAction(student,room,"leave",Map.of()),204);var history=get(student,path(room,id));code(history,200);assertThat((Object) read(history,"$.member")).isEqualTo(false);assertThat((Object) read(history,"$.problems[0].metadata")).isNull();assertThat((Object) read(history,"$.problems[0].slug")).isNull();assertThat((Object) read(history,"$.problems[0].grade.attempts")).isEqualTo(1);
        code(submit(student,room,id,SLUG,UUID.randomUUID().toString()),404);code(get(student,path(room,id)+"/problems/"+SLUG+"/solution"),404);code(get(student,"/api/v1/submissions/"+read(queued,"$.submissionId")),200);
        code(write(student,"POST","/api/v1/classrooms/join",Map.of("inviteCode",invite)),200);assertThat((Object) read(get(student,path(room,id)),"$.member")).isEqualTo(true);assertThat(db().queryForObject("SELECT COUNT(*) FROM assignment_participant WHERE assignment_id=? AND user_id=?",Integer.class,id,studentId)).isEqualTo(1);code(get(late,path(room,id)),404);
    }
    @Test void archiveStopsScheduledAndEndsActiveWithoutResurrectionAndCopyGetsFreshDraft() throws Exception {
        String room=room();join(student,room);String active=active(room,false,true,"AFTER_DEADLINE",future(120));String scheduled=id(create(room,UUID.randomUUID().toString(),definition(false,true,"AFTER_AC",future(120))));code(publish(room,scheduled,1,future(60)),200);
        code(roomAction(owner,room,"archive",Map.of()),204);assertThat((Object) read(get(student,path(room,active)),"$.assignment.status")).isEqualTo("ENDED");assertThat(db().queryForObject("SELECT status FROM classroom_assignment WHERE id=?",String.class,scheduled)).isEqualTo("STOPPED");code(submit(student,room,active,SLUG,UUID.randomUUID().toString()),409);
        code(roomAction(owner,room,"restore",Map.of()),204);lifecycle.refresh(room);assertThat((Object) read(get(student,path(room,active)),"$.assignment.status")).isEqualTo("ENDED");
        var copy=write(owner,"POST",path(room,active)+"/copy",Map.of("clientRequestId",UUID.randomUUID().toString(),"deadlineAt",future(180)));code(copy,201);assertThat((Object) read(copy,"$.assignment.status")).isEqualTo("DRAFT");assertThat((Object) read(copy,"$.participating")).isEqualTo(false);
    }
    @Test void currentVersionChangeRequiresDraftReviewAndStartedFormalUsesFrozenVersion() throws Exception {
        String room=room();join(student,room);String id=id(create(room,UUID.randomUUID().toString(),definition(false,true,"AFTER_AC",future(120))));long newer=advanceVersion();code(publish(room,id,1,null),409);code(edit(room,id,1,definition(false,true,"AFTER_AC",future(180))),200);code(publish(room,id,2,null),200);advanceVersion();
        var submitted=submit(student,room,id,SLUG,UUID.randomUUID().toString());code(submitted,202);assertThat(db().queryForObject("SELECT judge_version_id FROM submission WHERE id=?",Long.class,(String)read(submitted,"$.submissionId"))).isEqualTo(newer);
        assertThat(db().queryForObject("SELECT judge_version_id FROM assignment_attempt WHERE submission_id=?",Long.class,(String)read(submitted,"$.submissionId"))).isEqualTo(newer);
    }
    @Test void actualSqlDenialsKeepPublicationAndParticipantsImmutableAndWorkerUnaware() {
        var api=database("forgeoj_api","m0-api-test-secret");var worker=database("forgeoj_worker","m0-worker-test-secret");
        for(String table:List.of("classroom_assignment","assignment_problem","assignment_participant","assignment_precompletion","assignment_attempt","assignment_policy_fence","assignment_draft_problem","assignment_self_test")) assertThatThrownBy(()->worker.queryForList("SELECT * FROM "+table+" LIMIT 0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        for(String query:List.of("DELETE FROM classroom_assignment WHERE 1=0","DELETE FROM assignment_problem WHERE 1=0","UPDATE assignment_problem SET metadata_text=metadata_text WHERE 1=0","DELETE FROM assignment_participant WHERE 1=0","UPDATE assignment_participant SET user_id=user_id WHERE 1=0","UPDATE assignment_attempt SET accepted_at=accepted_at WHERE 1=0","DELETE FROM assignment_precompletion WHERE 1=0","UPDATE classroom_assignment SET created_by=created_by WHERE 1=0")) assertThatThrownBy(()->api.update(query)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void assignmentSelfTestIsScopedAndNeverCreatesFormalGradeOrAttempt() throws Exception {
        String room=room();join(student,room);String id=active(room,false,true,"IMMEDIATE",future(120)),key=UUID.randomUUID().toString();
        var body=Map.of("requestId",key,"language","JAVA_21","sourceCode",CODE,"input","1 2\n");long submissions=count("submission");
        var run=write(student,"POST",path(room,id)+"/problems/"+SLUG+"/self-tests",body);code(run,202);code(write(student,"POST",path(room,id)+"/problems/"+SLUG+"/self-tests",body),202);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM assignment_self_test WHERE assignment_id=?",Integer.class,id)).isEqualTo(1);assertThat(count("submission")).isEqualTo(submissions);
        assertThat((Object)read(get(student,path(room,id)),"$.problems[0].grade.state")).isEqualTo("NOT_STARTED");assertThat((Object)read(get(student,path(room,id)),"$.problems[0].grade.attempts")).isEqualTo(0);
        String another=active(room,false,true,"IMMEDIATE",future(120));code(write(student,"POST",path(room,another)+"/problems/"+SLUG+"/self-tests",body),409);
        code(get(owner,"/api/v1/self-tests/"+(String)read(run,"$.runId")),404);
    }
    @Test void changingScheduledAssignmentToImmediateUpdatesPlannedStartAndSnapshotsNow() throws Exception {
        String room=room();join(student,room);String id=id(create(room,UUID.randomUUID().toString(),definition(false,true,"AFTER_AC",future(120))));code(publish(room,id,1,future(60)),200);
        var immediate=publish(room,id,2,null);code(immediate,200);assertThat((Object)read(immediate,"$.assignment.status")).isEqualTo("ACTIVE");assertThat((Object)read(immediate,"$.assignment.startsAt")).isEqualTo(read(immediate,"$.assignment.startedAt"));
        assertThat(db().queryForObject("SELECT COUNT(*) FROM assignment_participant WHERE assignment_id=?",Integer.class,id)).isEqualTo(2);
    }
    @Test void oldVersionAcDoesNotPrecompleteAndScheduledInvalidBasisStopsWithoutParticipants() throws Exception {
        String room=room();join(student,room);String old=publicSubmission(student);finish(old,"AC");advanceVersion();
        String id=active(room,true,true,"IMMEDIATE",future(120));assertThat((Object)read(get(student,path(room,id)),"$.problems[0].grade.state")).isEqualTo("NOT_STARTED");
        String scheduled=id(create(room,UUID.randomUUID().toString(),definition(false,true,"AFTER_AC",future(120))));code(publish(room,scheduled,1,future(2)),200);advanceVersion();Thread.sleep(2300);lifecycle.refresh(room);
        assertThat(db().queryForObject("SELECT status FROM classroom_assignment WHERE id=?",String.class,scheduled)).isEqualTo("STOPPED");assertThat(db().queryForObject("SELECT COUNT(*) FROM assignment_participant WHERE assignment_id=?",Integer.class,scheduled)).isZero();
    }
    // Direct terminal fixtures above test relational/permission/time predicates only.
    // Delivery acceptance must obtain actual AC/PASSED from the real Worker.
    private void finish(String id,String verdict) {db().update("UPDATE submission SET processing_status='FINISHED',verdict=?,status_version=2,finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",verdict,id);db().update("UPDATE judge_task SET task_status='FINISHED',status_version=2,finished_at=CURRENT_TIMESTAMP(6) WHERE submission_id=?",id);}
    private long advanceVersion() {db().update("INSERT INTO problem_judge_version(problem_id,version_no,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256) SELECT problem_id,(SELECT MAX(version_no)+1 FROM problem_judge_version WHERE problem_id=1),time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256 FROM problem_judge_version WHERE id=1");long id=db().queryForObject("SELECT MAX(id) FROM problem_judge_version WHERE problem_id=1",Long.class);db().update("UPDATE problem SET current_judge_version_id=? WHERE id=1",id);return id;}
    private static Map<String,Object> definition(boolean existing,boolean late,String policy,String deadline) {return Map.of("title","原创作业","description","original assignment description","deadlineAt",deadline,"acceptExistingAc",existing,"allowLate",late,"solutionPolicy",policy,"problemSlugs",List.of(SLUG));}
    private String active(String room,boolean existing,boolean late,String policy,String deadline) throws Exception {String id=id(create(room,UUID.randomUUID().toString(),definition(existing,late,policy,deadline)));code(publish(room,id,1,null),200);return id;}
    private HttpResponse<String> create(String room,String key,Map<String,Object> d) throws Exception {return write(owner,"POST",path(room,null),Map.of("clientRequestId",key,"definition",d));}
    private HttpResponse<String> edit(String room,String id,long v,Map<String,Object> d) throws Exception {return write(owner,"PUT",path(room,id),Map.of("expectedVersion",v,"definition",d));}
    private HttpResponse<String> publish(String room,String id,long v,String start) throws Exception {var b=new LinkedHashMap<String,Object>();b.put("expectedVersion",v);b.put("startsAt",start);return write(owner,"POST",path(room,id)+"/publish",b);}
    private HttpResponse<String> submit(Browser b,String room,String id,String slug,String key) throws Exception {return write(b,"POST",path(room,id)+"/problems/"+slug+"/submissions",Map.of("clientRequestId",key,"language","JAVA_21","sourceCode",CODE));}
    private String publicSubmission(Browser b) throws Exception {var r=send(b,"POST","/api/v1/problems/"+SLUG+"/submissions",Map.of("language","JAVA_21","sourceCode",CODE),true,true,UUID.randomUUID().toString());code(r,202);return read(r,"$.submissionId");}
    private String room() throws Exception {var r=write(owner,"POST","/api/v1/classrooms",Map.of("title","原创作业班级","clientRequestId",UUID.randomUUID().toString()));code(r,201);return read(r,"$.id");}
    private String invite(String room) throws Exception {var r=roomAction(owner,room,"invite",Map.of("enabled",true));code(r,200);return read(r,"$.inviteCode");}
    private void join(Browser b,String room) throws Exception {code(write(b,"POST","/api/v1/classrooms/join",Map.of("inviteCode",invite(room))),200);}
    private HttpResponse<String> roomAction(Browser b,String room,String action,Map<String,Object> extra) throws Exception {var body=new LinkedHashMap<>(extra);body.put("expectedVersion",roomVersion(room));return write(b,"POST","/api/v1/classrooms/"+room+"/"+action,body);}
    private long roomVersion(String room) {return db().queryForObject("SELECT version FROM classroom WHERE id=?",Long.class,room);}
    private long assignmentVersion(String id) {return db().queryForObject("SELECT version FROM classroom_assignment WHERE id=?",Long.class,id);}
    private static String path(String room,String id) {return "/api/v1/classrooms/"+room+"/assignments"+(id==null?"":"/"+id);}
    private static String id(HttpResponse<String> r) {code(r,201);return read(r,"$.assignment.id");}
    private static String future(int seconds) {return Instant.now().plusSeconds(seconds).toString();}
    private static <T> T read(HttpResponse<String> r,String key) {return JsonPath.read(r.body(),key);}
    private long count(String table) {return db().queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private Browser login(String username) throws Exception {var b=new Browser();b.csrf=read(get(b,"/api/v1/auth/session"),"$.csrf.token");var r=write(b,"POST","/api/v1/auth/login",Map.of("username",username,"password","forgeoj-dev-only"));code(r,200);b.csrf=read(r,"$.csrf.token");return b;}
    private HttpResponse<String> get(Browser b,String path) throws Exception {return send(b,"GET",path,null,false,false,null);}
    private HttpResponse<String> write(Browser b,String method,String path,Object body) throws Exception {return send(b,method,path,body,true,true,null);}
    private HttpResponse<String> send(Browser b,String method,String path,Object body,boolean origin,boolean csrf,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");if(b!=null&&!b.cookies.isEmpty()) request.header("Cookie",String.join("; ",b.cookies.values()));if(origin) request.header("Origin","http://localhost:"+port);if(csrf&&b!=null&&b.csrf!=null) request.header("X-CSRF-TOKEN",b.csrf);if(key!=null) request.header("Idempotency-Key",key);
        var result=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());if(b!=null) for(String cookie:result.headers().allValues("Set-Cookie")) {var pair=cookie.split(";",2)[0];b.cookies.put(pair.split("=",2)[0],pair);}return result;
    }
    private static void code(HttpResponse<String> r,int status) {assertThat(r.statusCode()).as(r.body()).isEqualTo(status);if(Set.of(400,401,403,404,503).contains(status)) assertThat(r.body()).isEmpty();}
    private JdbcTemplate db() {return database("forgeoj_migrator","m0-migrator-test-secret");}
    private JdbcTemplate database(String name,String password) {return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),name,password));}
    private static class Browser {final Map<String,String> cookies=new ConcurrentHashMap<>();String csrf;}
}
