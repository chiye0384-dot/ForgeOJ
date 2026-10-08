/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import java.util.*;
import com.forgeoj.api.admin.PublicReviewMapper;
import com.forgeoj.api.auth.AccountService;
import com.forgeoj.api.classroom.AssignmentSolutionGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import static com.forgeoj.api.content.ContentRecords.*;

/** Copy only the original author's currently published immutable version. */
@Service
public class PublicRevisionService {
    public record Published(long id,String slug,String title,String status,long version,boolean dataInvalid) {}
    public record Page(List<Published> items,int page,int size,long total) {}
    private final PublicReviewMapper publicData;
    private final ContentMapper drafts;
    private final ContentValidationMapper quota;
    private final ContentService content;
    private final AccountService accounts;
    private final AssignmentSolutionGuard guard;
    private final ObjectMapper json;
    public PublicRevisionService(PublicReviewMapper publicData,ContentMapper drafts,ContentValidationMapper quota,
            ContentService content,AccountService accounts,AssignmentSolutionGuard guard,ObjectMapper json){
        this.publicData=publicData;this.drafts=drafts;this.quota=quota;this.content=content;this.accounts=accounts;this.guard=guard;this.json=json;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(long owner,int page,int size){
        if(page<1||size<1||size>50)throw ContentService.bad();
        var items=publicData.ownedProblems(owner,size,(long)(page-1)*size).stream()
            .map(p->new Published(p.id(),p.slug(),p.title(),p.status(),p.version(),p.dataInvalid())).toList();
        return new Page(items,page,size,publicData.ownedProblemCount(owner));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Detail copy(long owner,String slug,long version,String kind,String request){
        if(slug==null||slug.length()>100||version<1||version>=9007199254740991L||kind==null||!Set.of("TEXT","CORRECTION").contains(kind))throw ContentService.bad();
        ContentService.uuid(request);accounts.requireCurrentWrite(owner);quota.lockQuota(owner).orElseThrow();
        long id=publicData.ownedProblemId(owner,slug).orElseThrow(ContentService::missing);
        var previous=publicData.revisionRequest(owner,request);
        if(previous.isPresent()){
            var p=previous.get();if(p.problemId()!=id||p.expectedVersion()!=version||!p.revisionKind().equals(kind))throw ContentService.conflict();
            return content.detail(owner,p.draftId());
        }
        guard.lock();var p=publicData.lockProblem(id).orElseThrow(ContentService::missing);
        if(p.authorId()==null||p.authorId()!=owner||p.version()!=version||p.snapshotId()==null||kind.equals("TEXT")&&p.dataInvalid())throw ContentService.conflict();
        if(drafts.count(owner)>=100)throw ContentService.conflict();
        var frozen=publicData.frozen(p.snapshotId()).orElseThrow(()->new IllegalStateException("Missing public snapshot"));
        var c=new Content(json.readValue(frozen.metadataText(),Metadata.class),publicData.snapshotReference(p.snapshotId()),frozen.solutionIdea(),frozen.solutionCode());
        String draft=UUID.randomUUID().toString();one(drafts.insert(owner,draft,c,json.writeValueAsString(c.metadata())));
        if(publicData.copyRevisionTests(draft,p.snapshotId())!=frozen.testCount()||frozen.testCount()<1)throw new IllegalStateException("Revision test copy mismatch");
        one(publicData.insertRevision(draft,id,owner,request,version,kind));return content.detail(owner,draft);
    }
    private static void one(int n){if(n!=1)throw new IllegalStateException("Revision copy mismatch");}
}
