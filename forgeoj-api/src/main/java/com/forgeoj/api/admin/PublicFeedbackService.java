/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.util.*;
import com.forgeoj.api.auth.AccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.forgeoj.api.admin.AdminInput.*;

@Service
public class PublicFeedbackService {
    public record Detail(PublicFeedbackMapper.Case feedbackCase,AdminService.Page<PublicFeedbackMapper.Feedback> reports) {}
    private final PublicFeedbackMapper mapper;
    private final PublicReviewMapper problems;
    private final AccountService accounts;
    private final AdminService admins;
    private final AdminAudit audit;
    private final com.forgeoj.api.auth.AccountRateLimiter limits;
    public PublicFeedbackService(PublicFeedbackMapper mapper,PublicReviewMapper problems,AccountService accounts,AdminService admins,AdminAudit audit,com.forgeoj.api.auth.AccountRateLimiter limits){this.mapper=mapper;this.problems=problems;this.accounts=accounts;this.admins=admins;this.audit=audit;this.limits=limits;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public PublicFeedbackMapper.Feedback report(long user,String slug,String request,String category,String body){
        slug(slug);uuid(request);if(category==null||!Set.of("AMBIGUITY","SAMPLE_ERROR","TEST_ERROR","RESOURCE_LIMIT","COPYRIGHT","OTHER").contains(category))throw error(400);body=body(body);accounts.requireCurrentWrite(user);limits.check("public-feedback-write:"+user,20,300);
        var old=mapper.request(user,request,slug);if(old.isPresent()){if(!old.get().category().equals(category)||!old.get().body().equals(body))throw error(409);return old.get();}if(mapper.used(user,request))throw error(409);
        long problem=mapper.lockPublic(slug).orElseThrow(()->error(404));
        // Recheck after locking: concurrent same request cannot create a second case/report.
        old=mapper.request(user,request,slug);if(old.isPresent()){if(!old.get().category().equals(category)||!old.get().body().equals(body))throw error(409);return old.get();}
        String id=mapper.open(problem).orElse(null);if(id==null){if(mapper.caseCount(problem)>=1000)throw error(409);id=UUID.randomUUID().toString();one(mapper.create(id,problem));}
        var previous=mapper.existing(id,user);if(previous.isPresent())throw error(409);
        var selected=mapper.lock(id).orElseThrow();if(selected.reports()>=1000)throw error(429);
        one(mapper.report(UUID.randomUUID().toString(),id,user,request,category,body));return mapper.existing(id,user).orElseThrow();
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public AdminService.Page<PublicFeedbackMapper.Feedback> mine(long user,String slug,int page,int size){slug(slug);page(page,size);accounts.requireCurrentWrite(user);limits.check("public-feedback-read:"+user,120,300);return new AdminService.Page<>(mapper.mine(user,slug,size,(long)(page-1)*size),page,size,mapper.mineCount(user,slug));}
    public AdminService.Page<PublicFeedbackMapper.Case> cases(int page,int size,String status){page(page,size);if(status!=null&&!Set.of("OPEN","CLOSED").contains(status))throw error(400);return admins.contentWork(a->{audit.content("FEEDBACK_CASES_READ",a.id(),"FEEDBACK_CASE",null,"feedback list",null,null);return new AdminService.Page<>(mapper.cases(status,size,(long)(page-1)*size),page,size,mapper.count(status));});}
    public Detail detail(String id,int page,int size){uuid(id);page(page,size);return admins.contentWork(a->{var c=mapper.find(id).orElseThrow(()->error(404));audit.content("FEEDBACK_CASE_READ",a.id(),"FEEDBACK_CASE",id,"selected feedback context",null,null);return new Detail(c,new AdminService.Page<>(mapper.reports(id,size,(long)(page-1)*size),page,size,c.reports()));});}
    public PublicFeedbackMapper.Case close(String id,long expected,String reason){uuid(id);version(expected);String why=reason(reason);return admins.contentWork(a->{
        var c=mapper.find(id).orElseThrow(()->error(404));problems.registerExistingPublic();problems.lockProblem(c.problemId()).orElseThrow(()->error(404));c=mapper.lock(id).orElseThrow(()->error(404));if(!c.status().equals("OPEN")||c.version()!=expected||c.version()>=MAX_VERSION)throw error(409);one(mapper.close(id,expected,why,a.id()));audit.content("FEEDBACK_CASE_CLOSED",a.id(),"FEEDBACK_CASE",id,why,"OPEN;version="+expected,"CLOSED;version="+(expected+1));return mapper.find(id).orElseThrow();});}
    private static void slug(String value){if(value==null||value.isBlank()||value.length()>80)throw error(404);}
    private static String body(String value){if(value==null||value.strip().isEmpty()||value.codePointCount(0,value.length())>2000||value.indexOf('\0')>=0)throw error(400);return value.strip();}
    private static void one(int n){if(n!=1)throw new IllegalStateException("Feedback write mismatch");}
}
