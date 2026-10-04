/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import java.util.UUID;
import com.forgeoj.api.auth.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ContentReviewService {
    private final ContentMapper drafts;
    private final ContentValidationMapper validations;
    private final ContentReviewMapper reviews;
    private final AccountService accounts;
    private final ObjectMapper json;
    public ContentReviewService(ContentMapper drafts,ContentValidationMapper validations,ContentReviewMapper reviews,AccountService accounts,ObjectMapper json) {
        this.drafts=drafts;this.validations=validations;this.reviews=reviews;this.accounts=accounts;this.json=json;
    }
    @Transactional
    public ContentReviewMapper.Review submit(long owner,String draft,long version,String job,String request) {
        uuid(draft,HttpStatus.NOT_FOUND);uuid(job,HttpStatus.BAD_REQUEST);uuid(request,HttpStatus.BAD_REQUEST);version(version);
        accounts.requireCurrentWrite(owner);
        if(validations.lockQuota(owner).isEmpty()) throw new IllegalStateException("Missing owner lock");
        var previous=reviews.request(owner,request);
        if(previous.isPresent()) {
            var r=previous.get();
            if(!r.draftId().equals(draft) || r.draftVersion()!=version || !r.validationJobId().equals(job)) throw status(HttpStatus.CONFLICT);
            return r;
        }
        var row=drafts.lock(owner,draft).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        if(row.version()!=version || !row.status().equals("DRAFT") || drafts.pendingReview(draft).isPresent()) throw status(HttpStatus.CONFLICT);
        var verified=reviews.validation(owner,draft,job).orElseThrow(()->status(HttpStatus.CONFLICT));
        if(verified.draftVersion()!=version || !"FINISHED".equals(verified.processingStatus()) || !"PASSED".equals(verified.validationStatus())
           || !"ACCEPTED".equals(verified.referenceResult()) || !"ACCEPTED".equals(verified.solutionResult())) throw status(HttpStatus.CONFLICT);
        long number=reviews.lastNumber(draft).orElse(0L)+1;version(number);
        String id=UUID.randomUUID().toString();
        one(reviews.insert(id,draft,owner,version,job,verified.snapshotId(),request,number));
        committed("content.review_submitted",id,number,version);
        return reviews.find(owner,draft,id).orElseThrow(()->new IllegalStateException("Missing review"));
    }
    @Transactional
    public ContentReviewMapper.Review withdraw(long owner,String draft,String review,long draftVersion,long reviewVersion) {
        uuid(draft,HttpStatus.NOT_FOUND);uuid(review,HttpStatus.NOT_FOUND);version(draftVersion);
        if(reviewVersion<0 || reviewVersion>9007199254740991L) throw status(HttpStatus.BAD_REQUEST);
        accounts.requireCurrentWrite(owner);
        if(validations.lockQuota(owner).isEmpty()) throw new IllegalStateException("Missing owner lock");
        var row=drafts.lock(owner,draft).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        var r=reviews.lock(owner,draft,review).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        if(r.draftVersion()!=draftVersion) throw status(HttpStatus.CONFLICT);
        if(r.status().equals("WITHDRAWN")) {
            if(reviewVersion!=r.version() && reviewVersion!=r.version()-1) throw status(HttpStatus.CONFLICT);
            return r;
        }
        if(row.version()!=draftVersion || reviewVersion!=r.version() || !drafts.pendingReview(draft).orElse("").equals(review)) throw status(HttpStatus.CONFLICT);
        one(reviews.withdraw(owner,draft,review,reviewVersion));
        committed("content.review_withdrawn",review,r.reviewNo(),r.draftVersion());
        return reviews.find(owner,draft,review).orElseThrow(()->new IllegalStateException("Missing withdrawn review"));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ContentReviewMapper.Page list(long owner,String draft,int page,int size) {
        uuid(draft,HttpStatus.NOT_FOUND);if(page<1 || size<1 || size>50) throw status(HttpStatus.BAD_REQUEST);
        drafts.find(owner,draft).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        long total=reviews.count(owner,draft),offset=((long)page-1)*size;
        return new ContentReviewMapper.Page(offset>=total?java.util.List.of():reviews.list(owner,draft,size,offset),page,size,total);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ContentReviewMapper.Detail detail(long owner,String draft,String review) {
        uuid(draft,HttpStatus.NOT_FOUND);uuid(review,HttpStatus.NOT_FOUND);
        var r=reviews.find(owner,draft,review).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        var s=reviews.frozen(owner,draft,review).orElseThrow(()->new IllegalStateException("Missing immutable review"));
        return new ContentReviewMapper.Detail(r,new ContentRecords.Content(json.readValue(s.metadataText(),ContentRecords.Metadata.class),s.referenceCode(),s.solutionIdea(),s.solutionCode()),s.testCount());
    }
    private static void one(int count) {if(count!=1) throw status(HttpStatus.CONFLICT);}
    private static void committed(String event,String review,long number,long version) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {org.slf4j.LoggerFactory.getLogger(ContentReviewService.class).atInfo()
                    .addKeyValue("event",event).addKeyValue("contentReviewId",review)
                    .addKeyValue("reviewNo",number).addKeyValue("draftVersion",version).log("Content review lifecycle committed");}
                catch(RuntimeException ignored) { /* Logging cannot change the committed HTTP outcome. */ }
            }
        });
    }
    private static void version(long version) {if(version<1 || version>9007199254740991L) throw status(HttpStatus.BAD_REQUEST);}
    private static void uuid(String id,HttpStatus code) {try {if(id==null || !UUID.fromString(id).toString().equals(id)) throw status(code);}catch(IllegalArgumentException invalid) {throw status(code);}}
    private static ResponseStatusException status(HttpStatus code) {return new ResponseStatusException(code);}
}
