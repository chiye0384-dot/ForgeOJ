/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.util.*;
import com.forgeoj.api.content.*;
import com.forgeoj.api.classroom.ClassroomProblemMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import static com.forgeoj.api.admin.AdminInput.*;

@Service
public class PublicReviewService {
    public record Detail(PublicReviewMapper.Review review,ContentRecords.Metadata metadata,String solutionIdea,
            String solutionCode,int testCount,ContentValidationMapper.Result validation,PublicReviewMapper.Revision revision) {}
    public record Reference(String reviewId,String sourceCode) {}
    private final PublicReviewMapper mapper;
    private final AdminService admins;
    private final AdminAudit audit;
    private final ContentMapper drafts;
    private final ContentValidationMapper jobs;
    private final ClassroomProblemMapper copies;
    private final ObjectMapper json;
    private final com.forgeoj.api.classroom.AssignmentSolutionGuard assignmentGuard;
    private final com.forgeoj.api.auth.AccountMapper authors;
    private final com.forgeoj.api.cache.CacheInvalidations cacheInvalidations;
    public PublicReviewService(PublicReviewMapper mapper,AdminService admins,AdminAudit audit,ContentMapper drafts,
            ContentValidationMapper jobs,ClassroomProblemMapper copies,ObjectMapper json,com.forgeoj.api.classroom.AssignmentSolutionGuard assignmentGuard,com.forgeoj.api.auth.AccountMapper authors,com.forgeoj.api.cache.CacheInvalidations cacheInvalidations){
        this.mapper=mapper;this.admins=admins;this.audit=audit;this.drafts=drafts;this.jobs=jobs;this.copies=copies;this.json=json;
        this.assignmentGuard=assignmentGuard;
        this.authors=authors;
        this.cacheInvalidations=cacheInvalidations;
    }
    public AdminService.Page<PublicReviewMapper.Review> list(int page,int size,String status){
        page(page,size);filter(status,Set.of("PENDING","APPROVED","REJECTED","WITHDRAWN"));
        return admins.contentWork(a->{audit.content("REVIEW_LIST_READ",a.id(),"CONTENT_REVIEW",null,"review list",null,null);
            return new AdminService.Page<>(mapper.reviews(status,size,(long)(page-1)*size),page,size,mapper.reviewCount(status));});
    }
    public Detail detail(String id){
        readId(id);return admins.contentWork(a->{var r=review(id);var f=frozen(r);audit.content("REVIEW_CASE_READ",a.id(),"CONTENT_REVIEW",id,"frozen review",null,null);
            return new Detail(r,metadata(f),f.solutionIdea(),f.solutionCode(),f.testCount(),jobs.result(r.ownerId(),r.draftId(),r.jobId()).orElseThrow(),mapper.revision(r.draftId()).orElse(null));});
    }
    public Reference reference(String id){
        readId(id);return admins.contentWork(a->{review(id);audit.content("REVIEW_REFERENCE_READ",a.id(),"CONTENT_REVIEW",id,"explicit bound reference",null,null);return new Reference(id,mapper.reference(id));});
    }
    public PublicReviewMapper.Review decide(String id,long expected,String action,String request,String reason){
        readId(id);reviewVersion(expected);uuid(request);filter(action,Set.of("APPROVED","REJECTED"));if(action==null)throw error(400);String why=reason(reason),hash=digest(id+"\n"+expected+"\n"+action+"\n"+why);
        return admins.contentWork(a->{
            var previous=mapper.decision(a.id(),request);
            if(previous.isPresent()){
                var p=previous.get();if(!p.reviewId().equals(id)||!p.requestSha256().equals(hash))throw error(409);
                audit.content("REVIEW_DECISION_REPLAY",a.id(),"CONTENT_REVIEW",id,why,null,p.decision());return review(id);
            }
            var before=review(id);lockDraft(before);var r=mapper.lockReview(id).orElseThrow(()->error(404));
            if(r.version()!=expected||!r.status().equals("PENDING"))throw error(409);
            var draft=drafts.lock(r.ownerId(),r.draftId()).orElseThrow(()->error(409));
            if(draft.version()!=r.draftVersion()||!draft.status().equals("UNDER_REVIEW")||!drafts.pendingReview(r.draftId()).orElse("").equals(id))throw error(409);
            Long problem=null;
            if(action.equals("APPROVED")){
                if(!Boolean.TRUE.equals(mapper.passed(r.jobId(),r.snapshotId())))throw error(409);
                problem=publish(r,frozen(r),why);
                cacheInvalidations.publicChanged();
            }
            one(mapper.finish(id,expected,action));one(mapper.insertDecision(id,a.id(),request,hash,action,why,problem));
            audit.content(action.equals("APPROVED")?"REVIEW_APPROVED":"REVIEW_REJECTED",a.id(),"CONTENT_REVIEW",id,why,"PENDING;version="+expected,action+";version="+(expected+1)+";problem="+problem);
            return review(id);
        });
    }
    public ContentValidationMapper.Result recheck(String id,long expected,String request,String reason){
        readId(id);reviewVersion(expected);uuid(request);String why=reason(reason),hash=digest(id+"\n"+expected+"\n"+why);
        return admins.contentWork(a->{
            var r=review(id);lockDraft(r);var prior=mapper.recheck(a.id(),request);
            if(prior.isPresent()){if(!prior.get().reviewId().equals(id)||!prior.get().requestSha256().equals(hash))throw error(409);audit.content("REVIEW_RECHECK_REPLAY",a.id(),"CONTENT_REVIEW",id,why,null,null);return jobs.result(r.ownerId(),r.draftId(),prior.get().jobId()).orElseThrow();}
            r=mapper.lockReview(id).orElseThrow(()->error(404));
            if(!r.status().equals("PENDING")||r.version()!=expected||!Set.of("FINISHED","SYSTEM_ERROR").contains(mapper.jobStatus(r.jobId())))throw error(409);
            if(jobs.pending(r.ownerId())>=3)throw error(429);if(mapper.recheckCount(id)>=100)throw error(409);
            String job=UUID.randomUUID().toString(),event=UUID.randomUUID().toString();
            // Fresh server-generated ordinary job request avoids cross-domain request-ID collisions.
            one(jobs.job(job,r.snapshotId(),r.ownerId(),UUID.randomUUID().toString()));one(mapper.insertRecheck(a.id(),request,id,job,hash));one(mapper.latest(id,job));
            one(jobs.outbox(event,job,json.writeValueAsString(Map.of("taskId",job,"snapshotId",r.snapshotId(),"taskType","CONTENT_VALIDATE","contractVersion",1))));
            audit.content("REVIEW_RECHECK_CREATED",a.id(),"CONTENT_REVIEW",id,why,null,"job="+job+";outbox="+event);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){try{org.slf4j.LoggerFactory.getLogger(PublicReviewService.class).atInfo()
                    .addKeyValue("event","content.review_recheck_created").addKeyValue("reviewId",id).addKeyValue("contentJobId",job).addKeyValue("outboxEventId",event)
                    .log("Frozen review recheck committed");}catch(RuntimeException ignored){ /* Logging does not change the committed result. */ }}
            });
            return jobs.result(r.ownerId(),r.draftId(),job).orElseThrow();
        });
    }
    public AdminService.Page<PublicReviewMapper.Problem> problems(int page,int size,String status){
        page(page,size);filter(status,Set.of("ACTIVE","ARCHIVED"));return admins.contentWork(a->{mapper.registerExistingPublic();audit.content("PUBLIC_PROBLEMS_READ",a.id(),"PUBLIC_PROBLEM",null,"public governance list",null,null);return new AdminService.Page<>(mapper.problems(status,size,(long)(page-1)*size),page,size,mapper.problemCount(status));});
    }
    public PublicReviewMapper.Problem state(long id,long expected,String action,String reason){
        version(expected);String why=reason(reason);if(id<1||id>MAX_VERSION||!Set.of("ARCHIVE","RESTORE","INVALIDATE").contains(action))throw error(400);
        return admins.contentWork(a->{assignmentGuard.lock();mapper.registerExistingPublic();var p=mapper.lockProblem(id).orElseThrow(()->error(404));if(p.version()!=expected||p.version()>=MAX_VERSION)throw error(409);
            String next=action.equals("RESTORE")?"ACTIVE":"ARCHIVED";
            if(action.equals("RESTORE")&&(p.dataInvalid()||!p.status().equals("ARCHIVED"))||action.equals("ARCHIVE")&&!p.status().equals("ACTIVE")||action.equals("INVALIDATE")&&p.dataInvalid())throw error(409);
            if(action.equals("INVALIDATE"))one(mapper.invalidate(id,expected,why));else one(mapper.bump(id,expected,why));one(mapper.status(id,next));
            cacheInvalidations.publicChanged();
            audit.content("PUBLIC_"+action,a.id(),"PUBLIC_PROBLEM",Long.toString(id),why,p.status()+";invalid="+p.dataInvalid()+";version="+p.version(),next+";invalid="+(p.dataInvalid()||action.equals("INVALIDATE"))+";version="+(p.version()+1));return mapper.lockProblem(id).orElseThrow();});
    }
    private long publish(PublicReviewMapper.Review r,PublicReviewMapper.Frozen f,String reason){
        if(f.testCount()<1)throw error(409);var m=metadata(f);var revision=mapper.revision(r.draftId());
        if(revision.isPresent()&&revision.get().revisionKind().equals("TEXT")){
            assignmentGuard.lock();
            var v=revision.get();var p=mapper.lockProblem(v.problemId()).orElseThrow(()->error(409));
            if(v.ownerId()!=r.ownerId()||p.authorId()==null||p.authorId()!=r.ownerId()||p.version()!=v.expectedVersion()||p.dataInvalid()||p.version()>=MAX_VERSION)throw error(409);
            var old=mapper.frozen(p.snapshotId()).orElseThrow(()->error(409));var om=metadata(old);
            if(!sameJudge(old,f)||!om.inputDescription().equals(m.inputDescription())||!om.outputDescription().equals(m.outputDescription()))throw error(409);
            one(mapper.text(p.id(),m.title(),m.statement(),m.inputDescription(),m.outputDescription(),json.writeValueAsString(m.samples())));
            one(mapper.solutionText(p.judgeVersionId(),f.solutionIdea(),f.solutionCode()));one(mapper.updateRevision(p.id(),p.version(),r.id(),r.snapshotId()));return p.id();
        }
        Long corrected=null;
        if(revision.isPresent()){
            assignmentGuard.lock();
            var v=revision.get();var p=mapper.lockProblem(v.problemId()).orElseThrow(()->error(409));
            if(v.ownerId()!=r.ownerId()||p.authorId()==null||p.authorId()!=r.ownerId()||p.version()!=v.expectedVersion()||p.version()>=MAX_VERSION)throw error(409);
            one(mapper.bump(p.id(),p.version(),reason));one(mapper.status(p.id(),"ARCHIVED"));corrected=p.id();
        }
        String slug="public-"+UUID.randomUUID();one(mapper.problem(slug,m.title(),m.statement(),m.inputDescription(),m.outputDescription(),json.writeValueAsString(m.samples())));long id=mapper.problemId(slug);
        one(copies.judge(id,r.snapshotId()));long judge=copies.judgeId(id);if(copies.tests(judge,r.snapshotId())!=f.testCount())throw new IllegalStateException("Public frozen test copy mismatch");
        one(mapper.activate(id,judge));one(mapper.solution(judge,f.solutionIdea(),f.solutionCode()));one(mapper.governance(id,r.ownerId(),r.id(),r.snapshotId(),corrected));return id;
    }
    private void lockDraft(PublicReviewMapper.Review r){
        // Publication/recheck inserts have an author-account FK. Match ordinary
        // account -> quota -> draft order before those implicit FK locks occur.
        authors.lockAccount(r.ownerId()).orElseThrow(()->error(404));jobs.lockQuota(r.ownerId()).orElseThrow();drafts.lock(r.ownerId(),r.draftId()).orElseThrow(()->error(404));
    }
    private PublicReviewMapper.Review review(String id){return mapper.review(id).orElseThrow(()->error(404));}
    private PublicReviewMapper.Frozen frozen(PublicReviewMapper.Review r){return mapper.frozen(r.snapshotId()).orElseThrow(()->new IllegalStateException("Missing frozen review"));}
    private ContentRecords.Metadata metadata(PublicReviewMapper.Frozen f){return json.readValue(f.metadataText(),ContentRecords.Metadata.class);}
    private static boolean sameJudge(PublicReviewMapper.Frozen a,PublicReviewMapper.Frozen b){return a.dataset().equals(b.dataset())&&a.image().equals(b.image())&&a.timeLimitMs()==b.timeLimitMs()&&a.memoryLimitMb()==b.memoryLimitMb()&&a.outputLimitBytes()==b.outputLimitBytes()&&a.comparison().equals(b.comparison())&&a.sandbox().equals(b.sandbox());}
    private static void reviewVersion(long value){if(value<0||value>=MAX_VERSION)throw error(400);}
    private static void filter(String value,Set<String> values){if(value!=null&&!values.contains(value))throw error(400);}
    private static void readId(String id){try{uuid(id);}catch(org.springframework.web.server.ResponseStatusException invalid){throw error(404);}}
    private static void one(int n){if(n!=1)throw new IllegalStateException("Public governance write mismatch");}
}
