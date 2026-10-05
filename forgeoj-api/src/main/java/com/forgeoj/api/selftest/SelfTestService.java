/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.selftest;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import com.forgeoj.api.auth.AccountService;
import com.forgeoj.api.submission.SubmissionService;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class SelfTestService {
    private final SelfTestMapper mapper;
    private final AccountService accounts;
    private final ObjectMapper json;
    public SelfTestService(SelfTestMapper mapper,AccountService accounts,ObjectMapper json){this.mapper=mapper;this.accounts=accounts;this.json=json;}
    @Transactional
    public SelfTestMapper.Run create(long owner,String slug,String request,String language,String code,String input) {
        uuid(request,HttpStatus.BAD_REQUEST);slug(slug);
        if(!"JAVA_21".equals(language)) throw status(HttpStatus.BAD_REQUEST);
        SubmissionService.validateSource(code);
        if(input==null || input.indexOf('\0')>=0 || input.getBytes(StandardCharsets.UTF_8).length>1048576) throw status(HttpStatus.BAD_REQUEST);
        accounts.requireCurrentWrite(owner);
        if(mapper.quota(owner).isEmpty()) throw new IllegalStateException("Missing self-test quota");
        String sourceHash=hash(code),inputHash=hash(input);
        var previous=mapper.request(owner,request);
        if(previous.isPresent()) {
            var old=previous.get();
            if(!old.problemSlug().equals(slug) || !old.language().equals(language) || !old.sourceSha256().equals(sourceHash) || !old.inputSha256().equals(inputHash)) throw status(HttpStatus.CONFLICT);
            if(old.expired()) throw status(HttpStatus.GONE);
            return mapper.run(owner,old.runId()).orElseThrow(()->status(HttpStatus.GONE));
        }
        var v=mapper.version(slug).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        if(mapper.pending(owner)>=3) throw status(HttpStatus.TOO_MANY_REQUESTS);
        String snapshot=UUID.randomUUID().toString(),id=UUID.randomUUID().toString(),event=UUID.randomUUID().toString();
        long limit=Math.min(v.outputLimitBytes(),1048576),bytes=input.getBytes(StandardCharsets.UTF_8).length;
        String digest=hash(framed(Long.toString(owner),Long.toString(v.problemId()),slug,Long.toString(v.judgeVersionId()),language,sourceHash,inputHash,Long.toString(bytes),Integer.toString(v.timeLimitMs()),Integer.toString(v.memoryLimitMb()),Long.toString(limit),v.javaImageDigest(),v.comparisonRuleVersion(),v.sandboxPolicyVersion()));
        one(mapper.snapshot(new SelfTestMapper.Snapshot(snapshot,owner,v.problemId(),slug,v.judgeVersionId(),language,sourceHash,inputHash,bytes,v.timeLimitMs(),v.memoryLimitMb(),limit,v.javaImageDigest(),v.comparisonRuleVersion(),v.sandboxPolicyVersion(),digest)));
        one(mapper.insertPayload(snapshot,code,input));one(mapper.job(id,snapshot,owner,request));
        one(mapper.outbox(event,id,json.writeValueAsString(Map.of("taskId",id,"snapshotId",snapshot,"taskType","SELF_TEST","contractVersion",1))));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){try{LoggerFactory.getLogger(SelfTestService.class).atInfo().addKeyValue("event","selftest.created").addKeyValue("selfTestRunId",id).addKeyValue("outboxEventId",event).log("Self-test creation committed");}catch(RuntimeException ignored){}}});
        return mapper.run(owner,id).orElseThrow(()->new IllegalStateException("Missing self-test"));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SelfTestMapper.Detail detail(long owner,String id) {
        uuid(id,HttpStatus.NOT_FOUND);var run=mapper.run(owner,id).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        var payload=mapper.payload(owner,id).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        if(payload.sourceCode().getBytes(StandardCharsets.UTF_8).length>65536 || payload.input().getBytes(StandardCharsets.UTF_8).length!=payload.inputBytes() || payload.inputBytes()>1048576 || !hash(payload.sourceCode()).equals(payload.sourceSha256()) || !hash(payload.input()).equals(payload.inputSha256())) throw new IllegalStateException("Invalid self-test payload integrity");
        var output=mapper.output(id);String text=null;
        if("SUCCESS".equals(run.executionResult())) {
            var row=output.orElseThrow(()->new IllegalStateException("Missing self-test output"));
            if(row.bytes()<0 || row.bytes()>1048576 || row.gzip()==null || row.gzip().length>2097152) throw new IllegalStateException("Invalid output bound");
            try(var stream=new GZIPInputStream(new ByteArrayInputStream(row.gzip()))) {
                byte[] bytes=stream.readNBytes((int)row.bytes()+1);
                if(bytes.length!=row.bytes() || stream.read()!=-1 || !hash(bytes).equals(row.sha256())) throw new IllegalStateException("Invalid self-test output integrity");
                text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if(text.indexOf('\0')>=0) throw new IllegalStateException("Invalid output text");
            }catch(IOException e){throw new IllegalStateException("Invalid compressed self-test output");}
        } else if(output.isPresent()) throw new IllegalStateException("Unexpected self-test output");
        return new SelfTestMapper.Detail(run,payload.sourceCode(),payload.input(),text);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SelfTestMapper.Page list(long owner,String slug,int page,int size) {
        slug(slug);if(page<1 || size<1 || size>50) throw status(HttpStatus.BAD_REQUEST);
        long total=mapper.count(owner,slug),offset=((long)page-1)*size;
        return new SelfTestMapper.Page(offset>=total?List.of():mapper.list(owner,slug,size,offset),page,size,total);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public SelfTestMapper.Run cancel(long owner,String id) {
        uuid(id,HttpStatus.NOT_FOUND);accounts.requireCurrentWrite(owner);
        mapper.lock(owner,id).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        var run=mapper.run(owner,id).orElseThrow(()->status(HttpStatus.NOT_FOUND));
        if("CANCELLED".equals(run.processingStatus())) return run;
        if(!"QUEUED".equals(run.processingStatus())) throw status(HttpStatus.CONFLICT);
        one(mapper.cancel(owner,id));return mapper.run(owner,id).orElseThrow();
    }
    static String framed(String... values){var out=new StringBuilder();for(String value:values) out.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);return out.toString();}
    static String hash(String value){return hash(value.getBytes(StandardCharsets.UTF_8));}
    private static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException e){throw new IllegalStateException("Missing SHA-256");}}
    private static void uuid(String id,HttpStatus code){try{if(id==null || !UUID.fromString(id).toString().equals(id)) throw status(code);}catch(IllegalArgumentException e){throw status(code);}}
    private static void slug(String slug){if(slug==null || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || slug.length()>128) throw status(HttpStatus.NOT_FOUND);}
    private static void one(int count){if(count!=1) throw new IllegalStateException("Self-test insert failed");}
    private static ResponseStatusException status(HttpStatus code){return new ResponseStatusException(code);}
}
