/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.forgeoj.api.auth.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ContentValidationService {
    public static final String IMAGE="eclipse-temurin:21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438";
    private final ContentMapper drafts;
    private final ContentValidationMapper validations;
    private final ContentService content;
    private final AccountService accounts;
    private final ObjectMapper json;
    public ContentValidationService(ContentMapper drafts,ContentValidationMapper validations,ContentService content,AccountService accounts,ObjectMapper json) {
        this.drafts=drafts;this.validations=validations;this.content=content;this.accounts=accounts;this.json=json;
    }
    @Transactional
    public ContentValidationMapper.Result create(long owner,String draft,long version,String request) {
        uuid(draft,HttpStatus.NOT_FOUND);uuid(request,HttpStatus.BAD_REQUEST);
        if(version<1 || version>9007199254740991L) throw status(HttpStatus.BAD_REQUEST);
        accounts.requireCurrentWrite(owner);
        if(validations.lockQuota(owner).isEmpty()) throw new IllegalStateException("Missing quota lock");
        var previous=validations.request(owner,request);
        if(previous.isPresent()) {
            if(!previous.get().draftId().equals(draft) || previous.get().draftVersion()!=version) throw status(HttpStatus.CONFLICT);
            return previous.get();
        }
        var row=drafts.lock(owner,draft).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        if(row.version()!=version || !row.status().equals("DRAFT") || drafts.pendingReview(draft).isPresent()) throw status(HttpStatus.CONFLICT);
        var metadata=json.readValue(row.metadata(),ContentRecords.Metadata.class);
        var frozen=new ContentRecords.Content(metadata,row.referenceCode(),row.solutionIdea(),row.solutionCode());
        ContentService.validate(frozen);
        if(metadata.statement().isBlank() || metadata.inputDescription().isBlank() || metadata.outputDescription().isBlank()
                || metadata.samples().isEmpty() || metadata.licenseStatement().isBlank()
                || (metadata.originType().equals("ADAPTED") && metadata.sourceUrl().isBlank())
                || row.referenceCode().isBlank() || row.solutionCode().isBlank() || row.solutionIdea().isBlank()) throw status(HttpStatus.BAD_REQUEST);
        // Recheck bounded compressed integrity before accepting immutable execution inputs.
        var tests=content.tests(owner,draft);
        if(tests.isEmpty()) throw status(HttpStatus.BAD_REQUEST);
        if(validations.pending(owner)>=3) throw status(HttpStatus.TOO_MANY_REQUESTS);
        StringBuilder manifest=new StringBuilder();
        for(var test:tests) manifest.append(test.sequence()).append(':').append(hash(test.input())).append(':').append(hash(test.expectedOutput())).append('\n');
        String metadataText=json.writeValueAsString(metadata),referenceHash=hash(row.referenceCode()),solutionHash=hash(row.solutionCode()),datasetHash=hash(manifest.toString());
        String snapshotHash=hash(framed(draft,Long.toString(owner),Long.toString(version),metadataText,row.solutionIdea(),referenceHash,solutionHash,datasetHash,IMAGE,"trim-trailing-whitespace-v1","m0-v1"));
        String snapshot=UUID.randomUUID().toString(),job=UUID.randomUUID().toString();
        requireOne(validations.snapshot(new ContentValidationMapper.Snapshot(snapshot,draft,owner,version,metadataText,row.referenceCode(),row.solutionIdea(),row.solutionCode(),referenceHash,solutionHash,datasetHash,snapshotHash,IMAGE,metadata.timeLimitMs(),metadata.memoryLimitMb(),metadata.outputLimitBytes())));
        if(validations.copyTests(owner,draft,snapshot)!=tests.size()) throw new IllegalStateException("Snapshot test copy mismatch");
        requireOne(validations.job(job,snapshot,owner,request));
        String event=UUID.randomUUID().toString();
        requireOne(validations.outbox(event,job,json.writeValueAsString(Map.of("taskId",job,"snapshotId",snapshot,"taskType","CONTENT_VALIDATE","contractVersion",1))));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {LoggerFactory.getLogger(ContentValidationService.class).atInfo()
                    .addKeyValue("event","content.validation_created").addKeyValue("contentJobId",job)
                    .addKeyValue("outboxEventId",event).addKeyValue("draftVersion",version)
                    .log("Author content validation committed");} catch(RuntimeException ignored) {
                    // Observability must not change the committed HTTP outcome.
                }
            }
        });
        return validations.result(owner,draft,job).orElseThrow(()->new IllegalStateException("Missing created validation"));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ContentValidationMapper.Page list(long owner,String draft,int page,int size) {
        uuid(draft,HttpStatus.NOT_FOUND);
        if(page<1 || size<1 || size>50) throw status(HttpStatus.BAD_REQUEST);
        drafts.find(owner,draft).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        long total=validations.count(owner,draft),offset=((long)page-1)*size;
        return new ContentValidationMapper.Page(offset>=total?List.of():validations.list(owner,draft,size,offset),page,size,total);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ContentValidationMapper.Result result(long owner,String draft,String job) {
        uuid(draft,HttpStatus.NOT_FOUND);uuid(job,HttpStatus.NOT_FOUND);
        return validations.result(owner,draft,job).orElseThrow(()->status(HttpStatus.NOT_FOUND));
    }
    static String framed(String... values) {var out=new StringBuilder();for(String value:values) out.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);return out.toString();}
    static String hash(String text) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("Missing SHA-256");}}
    private static void uuid(String id,HttpStatus code) {try{if(id==null || !UUID.fromString(id).toString().equals(id)) throw status(code);}catch(IllegalArgumentException e){throw status(code);}}
    private static void requireOne(int count) {if(count!=1) throw new IllegalStateException("Validation insert failed");}
    private static ResponseStatusException status(HttpStatus status) {return new ResponseStatusException(status);}
}
