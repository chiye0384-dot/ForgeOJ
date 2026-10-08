/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import com.forgeoj.api.auth.AccountService;
import com.forgeoj.api.content.ContentRecords;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class AssignmentService {
    private final AssignmentMapper mapper;
    private final ClassroomMapper rooms;
    private final AccountService accounts;
    private final AssignmentLifecycle lifecycle;
    private final AssignmentSolutionGuard guard;
    private final ObjectMapper json;
    public AssignmentService(AssignmentMapper mapper,ClassroomMapper rooms,AccountService accounts,AssignmentLifecycle lifecycle,AssignmentSolutionGuard guard,ObjectMapper json) {this.mapper=mapper;this.rooms=rooms;this.accounts=accounts;this.lifecycle=lifecycle;this.guard=guard;this.json=json;}
    public record Definition(String title,String description,String deadlineAt,boolean acceptExistingAc,boolean allowLate,String solutionPolicy,List<String> problemSlugs) {}
    public record Summary(String id,String title,String status,long version,String startsAt,String deadlineAt,String startedAt,String endedAt,String closeReason,boolean acceptExistingAc,boolean allowLate,String solutionPolicy) {}
    public record Grade(String state,int attempts,String completionSubmissionId,String firstAcSubmissionId,String firstAcAt) {}
    public record Item(int ordinal,String slug,ContentRecords.Metadata metadata,long judgeVersionId,Grade grade) {}
    public record Detail(Summary assignment,String description,boolean member,boolean teaching,boolean participating,List<Item> problems,List<ClassroomMapper.Member> eligibleMembers) {}
    public record Page(List<Summary> items,int page,int size,long total,String classroomTitle,String classroomStatus,boolean member,boolean teaching) {}
    public record Prepared(AssignmentMapper.Row assignment,AssignmentMapper.Problem problem) {}
    record TeachingContext(AssignmentMapper.Row assignment,String classroomTitle,String classroomStatus) {}
    private record Identity(ClassroomMapper.Room room,boolean member,boolean teaching) {}

    private Identity authorize(long user,String room,boolean teaching,boolean writing) {
        ClassroomService.uuid(room);accounts.requireCurrentWrite(user);guard.lock();
        var r=rooms.lock(room).orElseThrow(ClassroomService::missing);
        var m=rooms.member(room,user).orElseThrow(ClassroomService::missing);
        boolean current=m.status().equals("ACTIVE"),teacher=current&&Set.of("OWNER","ASSISTANT").contains(m.role());
        if(teaching&&!teacher) throw current?ClassroomService.forbidden():ClassroomService.missing();
        if(writing&&(!current||!r.status().equals("ACTIVE"))) throw current?ClassroomService.conflict():ClassroomService.missing();
        if(r.status().equals("ACTIVE")) lifecycle.refreshLocked(room);
        return new Identity(r,current,teacher);
    }
    private AssignmentMapper.Row row(String room,String id) {ClassroomService.uuid(id);return mapper.lock(room,id).orElseThrow(ClassroomService::missing);}
    private static void expected(AssignmentMapper.Row r,long version) {if(version<1||version>=ClassroomService.MAX_VERSION) throw ClassroomService.bad();if(r.version()!=version) throw ClassroomService.conflict();}
    public Page list(long user,String room,int page,int size) {
        if(page<1||size<1||size>50) throw ClassroomService.bad();var who=authorize(user,room,false,false);
        // Filter in SQL so paging never exposes other participants or hidden drafts.
        long count=mapper.visibleCount(room,user,who.member(),who.teaching()),offset=(long)(page-1)*size;
        var rows=offset>=count?List.<AssignmentMapper.Row>of():mapper.visible(room,user,who.member(),who.teaching(),size,offset);
        return new Page(rows.stream().map(this::summary).toList(),page,size,count,who.room().title(),who.room().status(),who.member(),who.teaching());
    }
    public Detail detail(long user,String room,String id) {
        var who=authorize(user,room,false,false);var r=row(room,id);
        boolean participant=mapper.participates(id,user);
        if(!who.teaching()&&!participant&&!(who.member()&&r.status().equals("SCHEDULED"))) throw ClassroomService.missing();
        return detail(user,r,who,participant);
    }
    TeachingContext teachingContext(long user,String room,String id) {
        var who=authorize(user,room,true,false);
        return new TeachingContext(row(room,id),who.room().title(),who.room().status());
    }
    private Detail detail(long user,AssignmentMapper.Row r,Identity who,boolean participant) {
        var items=mapper.problems(r.id()).stream().map(p->new Item(p.ordinal(),who.member()?p.problemSlug():null,who.member()&&(participant||who.teaching())?json.readValue(p.metadataText(),ContentRecords.Metadata.class):null,p.judgeVersionId(),participant?grade(user,r,p):null)).toList();
        return new Detail(summary(r),who.member()?r.description():"",who.member(),who.teaching(),participant,items,who.teaching()?rooms.members(r.classroomId(),false):List.of());
    }
    private Grade grade(long user,AssignmentMapper.Row r,AssignmentMapper.Problem p) {
        int count=mapper.attempts(r.id(),user,p.problemId());var pre=mapper.precompleted(r.id(),user,p.problemId());
        if(mapper.invalid(p.problemId())) return new Grade("INVALID",count,null,null,null);
        if(pre.isPresent()) return new Grade("PRECOMPLETED",count,pre.get(),null,null);
        var proof=mapper.ac(r.id(),user,p.problemId());
        if(proof.isEmpty()) return new Grade(count==0?"NOT_STARTED":"ATTEMPTING",count,null,null,null);
        var first=mapper.firstAc(r.id(),user,p.problemId()).orElseThrow();
        var cutoff=r.endedAt()==null||r.deadlineAt().isBefore(r.endedAt())?r.deadlineAt():r.endedAt();
        return new Grade(proof.get().acceptedAt().isBefore(cutoff)?"ON_TIME_AC":"LATE_AC",count,proof.get().submissionId(),first.submissionId(),time(first.finishedAt()));
    }
    public Detail create(long user,String room,String request,Definition input) {
        ClassroomService.uuid(request);var d=definition(input);var who=authorize(user,room,true,true);
        String hash=hash(room+"\n"+json.writeValueAsString(d));
        var old=mapper.request(user,request);
        if(old.isPresent()) {if(!old.get().classroomId().equals(room)||!mapper.creationHash(old.get().id()).equals(hash)) throw ClassroomService.conflict();return detail(user,old.get(),who,mapper.participates(old.get().id(),user));}
        if(mapper.count(room)>=1000||!parse(d.deadlineAt()).isAfter(mapper.now())) throw ClassroomService.conflict();
        var chosen=candidates(room,d.problemSlugs());String id=UUID.randomUUID().toString();
        one(mapper.create(id,room,user,request,hash,d.title(),d.description(),parse(d.deadlineAt()),d.acceptExistingAc(),d.allowLate(),d.solutionPolicy()));putProblems(id,chosen);
        return detail(user,row(room,id),who,false);
    }
    public Detail edit(long user,String room,String id,long version,Definition input) {
        var d=definition(input);var who=authorize(user,room,true,true);var r=row(room,id);expected(r,version);
        if(Set.of("CANCELLED","STOPPED").contains(r.status())) throw ClassroomService.conflict();
        var deadline=parse(d.deadlineAt());
        if(r.startedAt()!=null) {
            var slugs=mapper.problems(id).stream().map(AssignmentMapper.Problem::problemSlug).toList();
            if(!slugs.equals(d.problemSlugs())||r.acceptExistingAc()!=d.acceptExistingAc()||r.allowLate()!=d.allowLate()||!r.solutionPolicy().equals(d.solutionPolicy())||deadline.isBefore(r.deadlineAt())) throw ClassroomService.conflict();
        } else {
            if(!deadline.isAfter(mapper.now())||r.startsAt()!=null&&!deadline.isAfter(r.startsAt())) throw ClassroomService.conflict();
            var chosen=candidates(room,d.problemSlugs());mapper.removeProblems(id);putProblems(id,chosen);
        }
        one(mapper.edit(id,version,d.title(),d.description(),deadline,d.acceptExistingAc(),d.allowLate(),d.solutionPolicy()));
        if(r.status().equals("ENDED")&&"硬截止".equals(r.closeReason())&&deadline.isAfter(mapper.now())&&deadline.isAfter(r.deadlineAt())) one(mapper.extendNaturalEnd(id));
        return detail(user,row(room,id),who,mapper.participates(id,user));
    }
    public Detail publish(long user,String room,String id,long version,String startsAt) {
        var who=authorize(user,room,true,true);var r=row(room,id);expected(r,version);
        if(!Set.of("DRAFT","SCHEDULED").contains(r.status())||!lifecycle.valid(room,id)) throw ClassroomService.conflict();
        var now=mapper.now();var start=startsAt==null?now:parse(startsAt);
        if(!r.deadlineAt().isAfter(start)||!r.deadlineAt().isAfter(now)) throw ClassroomService.conflict();
        if(start.isAfter(now)) one(mapper.publish(id,"SCHEDULED",start,null));else lifecycle.start(r,now,now);
        return detail(user,row(room,id),who,mapper.participates(id,user));
    }
    public Detail cancel(long user,String room,String id,long version,String reason) {
        var who=authorize(user,room,true,true);var r=row(room,id);expected(r,version);
        reason=text(reason,500,false);if(!Set.of("DRAFT","SCHEDULED","ACTIVE").contains(r.status())) throw ClassroomService.conflict();
        one(mapper.close(id,"CANCELLED",mapper.now(),reason));return detail(user,row(room,id),who,mapper.participates(id,user));
    }
    public Detail add(long user,String room,String id,long version,long target) {
        var who=authorize(user,room,true,true);var r=row(room,id);expected(r,version);
        if(r.startedAt()==null||!r.status().equals("ACTIVE")) throw ClassroomService.conflict();
        rooms.member(room,target).filter(m->m.status().equals("ACTIVE")).orElseThrow(ClassroomService::missing);
        if(!mapper.participates(id,target)) {var now=mapper.now();one(mapper.participant(id,room,target,now));if(r.acceptExistingAc()) mapper.precomplete(id,target,now.plusSeconds(mapper.databaseOffsetSeconds()));one(mapper.bump(id,version));}
        return detail(user,row(room,id),who,mapper.participates(id,user));
    }
    public Detail copy(long user,String room,String id,String request,String deadline) {
        authorize(user,room,true,true);var r=row(room,id);
        return create(user,room,request,new Definition(r.title(),r.description(),deadline,r.acceptExistingAc(),r.allowLate(),r.solutionPolicy(),mapper.problems(id).stream().map(AssignmentMapper.Problem::problemSlug).toList()));
    }
    public Prepared prepare(long user,String room,String id,String slug) {
        return prepare(user,room,id,slug,false);
    }
    public Prepared prepare(long user,String room,String id,String slug,boolean replay) {
        var who=authorize(user,room,false,true);var r=row(room,id);
        if(!who.member()||!mapper.participates(id,user)) throw ClassroomService.missing();
        if(!replay&&!r.status().equals("ACTIVE")) throw ClassroomService.conflict();
        var p=mapper.problems(id).stream().filter(v->v.problemSlug().equals(slug)).findFirst().orElseThrow(ClassroomService::missing);
        if(mapper.activeProblem(p.problemId(),room)==0) throw ClassroomService.missing();
        return new Prepared(r,p);
    }
    public LocalDateTime acceptance(Prepared p) {var now=mapper.now();if(!p.assignment().allowLate()&&!now.isBefore(p.assignment().deadlineAt())) throw ClassroomService.conflict();return now;}
    public void link(long user,Prepared p,String submission,LocalDateTime accepted) {one(mapper.attempt(submission,p.assignment().id(),user,p.problem().problemId(),p.problem().judgeVersionId(),accepted));}
    public boolean linked(long user,Prepared p,String submission) {return mapper.linked(submission,p.assignment().id(),user,p.problem().problemId());}
    public boolean selfTestLinked(long user,Prepared p,String run) {return mapper.selfTestLinked(run,p.assignment().id(),user,p.problem().problemId());}
    public void linkSelfTest(long user,Prepared p,String run) {one(mapper.selfTest(run,p.assignment().id(),user,p.problem().problemId(),p.problem().judgeVersionId()));}
    public ClassroomProblemService.Solution solution(long user,String room,String id,String slug) {
        var who=authorize(user,room,false,false);var r=row(room,id);
        if(!who.member()||!mapper.participates(id,user)||r.startedAt()==null) throw ClassroomService.missing();
        var p=mapper.problems(id).stream().filter(v->v.problemSlug().equals(slug)).findFirst().orElseThrow(ClassroomService::missing);
        if(!guard.allowed(user,p.problemId())) return new ClassroomProblemService.Solution("LOCKED",null,null);
        var content=p.solutionSnapshotId()==null?mapper.publicSolution(p.judgeVersionId()):Optional.of(mapper.privateSolution(p.solutionSnapshotId()));
        return content.map(c->new ClassroomProblemService.Solution(r.solutionPolicy().equals("AFTER_AC")?"AC":r.solutionPolicy(),c.idea(),c.code())).orElseGet(()->new ClassroomProblemService.Solution("UNAVAILABLE",null,null));
    }
    private List<AssignmentMapper.Candidate> candidates(String room,List<String> slugs) {var chosen=slugs.stream().map(s->mapper.candidate(room,s).orElseThrow(ClassroomService::missing)).toList();if(chosen.stream().map(AssignmentMapper.Candidate::problemId).distinct().count()!=chosen.size()) throw ClassroomService.bad();return chosen;}
    private void putProblems(String id,List<AssignmentMapper.Candidate> problems) {for(int i=0;i<problems.size();i++) one(mapper.problem(id,problems.get(i),i+1));}
    private Definition definition(Definition d) {
        if(d==null||d.problemSlugs()==null||d.problemSlugs().isEmpty()||d.problemSlugs().size()>20||new HashSet<>(d.problemSlugs()).size()!=d.problemSlugs().size()||d.problemSlugs().stream().anyMatch(s->s==null||s.isBlank()||s.length()>80)||d.solutionPolicy()==null||!Set.of("IMMEDIATE","AFTER_AC","AFTER_DEADLINE").contains(d.solutionPolicy())) throw ClassroomService.bad();
        return new Definition(text(d.title(),100,false),text(d.description(),10000,true),time(parse(d.deadlineAt())),d.acceptExistingAc(),d.allowLate(),d.solutionPolicy(),List.copyOf(d.problemSlugs()));
    }
    private static String text(String value,int max,boolean blank) {if(value==null||value.indexOf('\0')>=0||value.codePointCount(0,value.length())>max) throw ClassroomService.bad();value=value.strip();if(!blank&&value.isEmpty()) throw ClassroomService.bad();return value;}
    private static LocalDateTime parse(String value) {try {return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.MICROS);}catch(RuntimeException e){throw ClassroomService.bad();}}
    private static String time(LocalDateTime value) {return value==null?null:value.toString()+"Z";}
    Summary summary(AssignmentMapper.Row r) {return new Summary(r.id(),r.title(),r.status(),r.version(),time(r.startsAt()),time(r.deadlineAt()),time(r.startedAt()),time(r.endedAt()),r.closeReason(),r.acceptExistingAc(),r.allowLate(),r.solutionPolicy());}
    private static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void one(int count) {if(count!=1) throw new IllegalStateException("Assignment write conflict");}
}
