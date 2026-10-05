/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import java.util.*;
import com.forgeoj.api.auth.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContentOutputService {
    private final ContentOutputMapper outputs;
    private final ContentValidationService executions;
    private final ContentValidationMapper validations;
    private final ContentMapper drafts;
    private final ContentService content;
    private final AccountService accounts;
    public ContentOutputService(ContentOutputMapper outputs,ContentValidationService executions,ContentValidationMapper validations,ContentMapper drafts,ContentService content,AccountService accounts) {
        this.outputs=outputs;this.executions=executions;this.validations=validations;this.drafts=drafts;this.content=content;this.accounts=accounts;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public ContentOutputMapper.Preview create(long owner,String draft,long version,String request) {
        var job=executions.createExecution(owner,draft,version,request,true);
        return outputs.result(owner,draft,job.jobId()).orElseThrow(()->new IllegalStateException("Missing output preview"));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ContentOutputMapper.Page list(long owner,String draft,int page,int size) {
        uuid(draft);if(page<1 || size<1 || size>50) throw status(HttpStatus.BAD_REQUEST);
        drafts.find(owner,draft).orElseThrow(()->status(HttpStatus.NOT_FOUND));long total=outputs.count(owner,draft),offset=((long)page-1)*size;
        return new ContentOutputMapper.Page(offset>=total?List.of():outputs.list(owner,draft,size,offset),page,size,total);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ContentOutputMapper.Detail detail(long owner,String draft,String job) {
        uuid(draft);uuid(job);var result=outputs.result(owner,draft,job).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        return new ContentOutputMapper.Detail(result,cases(owner,draft,job,result));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public ContentOutputMapper.Receipt accept(long owner,String draft,String job,long version) {
        uuid(draft);uuid(job);if(version<1 || version>=9007199254740991L) throw status(HttpStatus.BAD_REQUEST);
        accounts.requireCurrentWrite(owner);if(validations.lockQuota(owner).isEmpty()) throw new IllegalStateException("Missing quota lock");
        var row=drafts.lock(owner,draft).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        var previous=outputs.receipt(owner,draft,job);
        if(previous.isPresent()) {if(previous.get().expectedVersion()!=version) throw status(HttpStatus.CONFLICT);return previous.get();}
        var result=outputs.lock(owner,draft,job).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        if(row.version()!=version || result.draftVersion()!=version || !row.status().equals("DRAFT") || drafts.pendingReview(draft).isPresent()
            || !result.processingStatus().equals("FINISHED") || !"ACCEPTED".equals(result.referenceResult())) throw status(HttpStatus.CONFLICT);
        var generated=cases(owner,draft,job,result);
        content.replaceTests(owner,draft,version,generated.stream().map(c->new TestDatasetArchive.TestPair(c.sequence(),c.input(),c.generatedOutput())).toList());
        if(outputs.accept(owner,draft,job,version)!=1) throw new IllegalStateException("Output acceptance insert failed");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){try {org.slf4j.LoggerFactory.getLogger(ContentOutputService.class).atInfo().addKeyValue("event","content.output_accepted").addKeyValue("contentJobId",job).addKeyValue("draftVersion",version+1).log("Output preview accepted");}catch(RuntimeException ignored){}}
        });
        return new ContentOutputMapper.Receipt(job,draft,version,version+1);
    }
    private List<ContentOutputMapper.Case> cases(long owner,String draft,String job,ContentOutputMapper.Preview result) {
        var rows=outputs.outputs(owner,draft,job);if(rows.isEmpty() || rows.size()>100) throw new IllegalStateException("Invalid preview count");
        boolean success=result.processingStatus().equals("FINISHED") && "ACCEPTED".equals(result.referenceResult());
        var cases=new ArrayList<ContentOutputMapper.Case>();long total=0;
        for(var r:rows) {
            if(r.sequence()!=cases.size()+1 || (success && r.outputBytes()==null) || (!success && r.outputBytes()!=null)) throw new IllegalStateException("Incomplete preview output");
            String input=ContentService.unzip(r.inputGzip(),r.inputBytes(),r.inputSha256()),old=ContentService.unzip(r.oldGzip(),r.oldBytes(),r.oldSha256());
            String generated=success?ContentService.unzip(r.outputGzip(),r.outputBytes(),r.outputSha256()):null;
            total+=r.inputBytes()+(success?r.outputBytes():r.oldBytes());if(total>16L*1024*1024) throw new IllegalStateException("Preview dataset bound exceeded");
            cases.add(new ContentOutputMapper.Case(r.sequence(),input,old,generated));
        }
        return cases;
    }
    private static void uuid(String id) {try {if(id==null || !UUID.fromString(id).toString().equals(id)) throw status(HttpStatus.NOT_FOUND);}catch(IllegalArgumentException invalid){throw status(HttpStatus.NOT_FOUND);}}
    private static ResponseStatusException status(HttpStatus code){return new ResponseStatusException(code);}
}
