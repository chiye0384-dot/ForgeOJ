/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

import java.util.*;
import com.forgeoj.api.auth.AccountService;
import com.forgeoj.api.content.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class ClassroomProblemService {
    private final ClassroomMapper rooms;
    private final ClassroomProblemMapper mapper;
    private final AccountService accounts;
    private final ContentMapper drafts;
    private final ContentReviewMapper validations;
    private final ObjectMapper json;
    private final ContentService content;
    private final AssignmentSolutionGuard assignmentSolutions;
    public record Summary(String slug,String title,String status,long version,long createdBy,String solutionPolicy) {}
    public record Page(List<Summary> items,int page,int size,long total) {}
    public record Detail(Summary problem,ContentRecords.Metadata metadata) {}
    public record Maintenance(Detail detail,String referenceCode,String solutionIdea,String solutionCode,int testCount) {}
    public record Solution(String access,String idea,String sourceCode) {}
    public ClassroomProblemService(ClassroomMapper rooms,ClassroomProblemMapper mapper,AccountService accounts,
            ContentMapper drafts,ContentReviewMapper validations,ObjectMapper json,ContentService content,AssignmentSolutionGuard assignmentSolutions) {
        this.rooms=rooms;this.mapper=mapper;this.accounts=accounts;this.drafts=drafts;this.validations=validations;this.json=json;this.content=content;this.assignmentSolutions=assignmentSolutions;
    }
    // All private reads lock the same account/session/classroom/member order as writes.
    // This serializes authorization with leave, removal, archive and session revocation.
    public ClassroomMapper.Room authorize(long user,String room,boolean teaching,boolean writing) {
        ClassroomService.uuid(room);accounts.requireCurrentWrite(user);
        var r=rooms.lock(room).orElseThrow(ClassroomService::missing);
        var member=rooms.member(room,user).filter(m->m.status().equals("ACTIVE")).orElseThrow(ClassroomService::missing);
        if(teaching&&!Set.of("OWNER","ASSISTANT").contains(member.role())) throw ClassroomService.forbidden();
        if(writing&&!r.status().equals("ACTIVE")) throw ClassroomService.conflict();
        return r;
    }
    public Page list(long user,String room,int page,int size) {
        if(page<1||size<1||size>50) throw ClassroomService.bad();authorize(user,room,false,false);
        long count=mapper.count(room),offset=(long)(page-1)*size;
        return new Page(offset>=count?List.of():mapper.list(room,size,offset).stream().map(this::summary).toList(),page,size,count);
    }
    public Detail detail(long user,String room,String slug) {authorize(user,room,false,false);return detail(row(room,slug));}
    public Maintenance maintenance(long user,String room,String slug) {
        authorize(user,room,true,false);var r=row(room,slug);var f=frozen(r);
        return new Maintenance(detail(r),f.referenceCode(),f.solutionIdea(),f.solutionCode(),f.testCount());
    }
    public Solution solution(long user,String room,String slug) {
        accounts.requireCurrentWrite(user);assignmentSolutions.lock();
        authorize(user,room,false,false);var r=row(room,slug);
        if(!assignmentSolutions.allowed(user,r.id())) return new Solution("LOCKED",null,null);
        String access=r.solutionPolicy().equals("IMMEDIATE")?"IMMEDIATE":mapper.completed(user,r.id())?"AC":mapper.viewed(user,r.id())?"EARLY_VIEW":"LOCKED";
        if(access.equals("LOCKED")) return new Solution(access,null,null);
        var f=mapper.solution(r.snapshotId());return new Solution(access,f.idea(),f.code());
    }
    public Solution confirmSolution(long user,String room,String slug,long expectedVersion) {
        accounts.requireCurrentWrite(user);assignmentSolutions.lock();
        authorize(user,room,false,true);var r=row(room,slug);
        if(!assignmentSolutions.allowed(user,r.id())) throw ClassroomService.conflict();
        if(expectedVersion<1||expectedVersion>ClassroomService.MAX_VERSION) throw ClassroomService.bad();
        if(r.version()!=expectedVersion||!r.status().equals("ACTIVE")) throw ClassroomService.conflict();
        if(!mapper.completed(user,r.id())&&!r.solutionPolicy().equals("IMMEDIATE")) mapper.earlyView(user,r.id());
        return solution(user,room,slug);
    }
    public Summary publish(long user,String room,String draft,long version,String job,String request,String policy) {
        ClassroomService.uuid(draft);request(job);request(request);
        if(version<1||version>ClassroomService.MAX_VERSION||!Set.of("IMMEDIATE","AFTER_AC").contains(policy)) throw ClassroomService.bad();
        authorize(user,room,true,true);
        var prior=mapper.request(user,request);
        if(prior.isPresent()) {
            var p=prior.get();
            if(mapper.lock(room,p.slug()).isEmpty()||!p.draftId().equals(draft)||p.draftVersion()!=version||!p.validationJobId().equals(job)||!p.solutionPolicy().equals(policy)) throw ClassroomService.conflict();
            return summary(p);
        }
        if(mapper.count(room)>=1000) throw ClassroomService.conflict();
        var d=drafts.lock(user,draft).orElseThrow(ClassroomService::missing);
        if(d.version()!=version||!d.status().equals("DRAFT")||drafts.pendingReview(draft).isPresent()) throw ClassroomService.conflict();
        var validation=validations.validation(user,draft,job).orElseThrow(ClassroomService::conflict);
        if(validation.draftVersion()!=version||!"FINISHED".equals(validation.processingStatus())||!"PASSED".equals(validation.validationStatus())||!"ACCEPTED".equals(validation.referenceResult())||!"ACCEPTED".equals(validation.solutionResult())) throw ClassroomService.conflict();
        var f=mapper.frozen(validation.snapshotId()).orElseThrow();
        if(f.testCount()==0) throw ClassroomService.conflict();
        var m=json.readValue(f.metadataText(),ContentRecords.Metadata.class);
        String slug="class-"+UUID.randomUUID();
        one(mapper.problem(slug,room,m.title(),m.statement(),m.inputDescription(),m.outputDescription(),json.writeValueAsString(m.samples())));
        long id=mapper.identity(slug,room);one(mapper.judge(id,validation.snapshotId()));long judge=mapper.judgeId(id);
        if(mapper.tests(judge,validation.snapshotId())!=f.testCount()) throw new IllegalStateException("Private test copy mismatch");
        one(mapper.activate(id,judge));one(mapper.publish(id,room,user,draft,version,job,validation.snapshotId(),request,policy));
        return summary(row(room,slug));
    }
    public void archive(long user,String room,String slug,long version) {
        authorize(user,room,true,true);var r=row(room,slug);
        if(version<1||version>=ClassroomService.MAX_VERSION) throw ClassroomService.bad();
        if(r.version()!=version||!r.status().equals("ACTIVE")) throw ClassroomService.conflict();
        one(mapper.archive(r.id()));one(mapper.increment(r.id(),version));
    }
    // Copy into the current maintainer's private workspace. Editing cannot overwrite a published snapshot.
    public ContentRecords.Detail copy(long user,String room,String slug) {
        authorize(user,room,true,true);var r=row(room,slug);var f=frozen(r);
        if(drafts.count(user)>=100) throw ClassroomService.conflict();
        String id=UUID.randomUUID().toString();var m=json.readValue(f.metadataText(),ContentRecords.Metadata.class);
        var c=new ContentRecords.Content(m,f.referenceCode(),f.solutionIdea(),f.solutionCode());
        one(drafts.insert(user,id,c,f.metadataText()));
        if(mapper.copyDraftTests(id,r.snapshotId())!=f.testCount()) throw new IllegalStateException("Private draft copy mismatch");
        return content.detail(user,id);
    }
    private ClassroomProblemMapper.Row row(String room,String slug) {
        if(slug==null||slug.length()>80) throw ClassroomService.missing();return mapper.lock(room,slug).orElseThrow(ClassroomService::missing);
    }
    private ClassroomProblemMapper.Frozen frozen(ClassroomProblemMapper.Row r) {return mapper.frozen(r.snapshotId()).orElseThrow();}
    private Detail detail(ClassroomProblemMapper.Row r) {return new Detail(summary(r),json.readValue(mapper.metadata(r.snapshotId()),ContentRecords.Metadata.class));}
    private Summary summary(ClassroomProblemMapper.Row r) {return new Summary(r.slug(),r.title(),r.status(),r.version(),r.createdBy(),r.solutionPolicy());}
    private static void request(String id) {try {ClassroomService.uuid(id);}catch(org.springframework.web.server.ResponseStatusException e){throw ClassroomService.bad();}}
    private static void one(int n) {if(n!=1) throw new IllegalStateException("Private publication write mismatch");}
}
