/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.content;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import com.forgeoj.worker.snapshot.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
public class ValidationSnapshotLoader {
    public record Programs(JudgeTaskSnapshot reference,JudgeTaskSnapshot solution) {}
    private final ValidationMapper mapper;
    private final ObjectMapper json;
    public ValidationSnapshotLoader(ValidationMapper mapper,ObjectMapper json) {this.mapper=mapper;this.json=json;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Programs load(ValidationLeaseService.Claim claim) {
        var row=mapper.snapshot(claim.jobId(),claim.token(),claim.attemptId()).orElseThrow(()->invalid("Validation inputs unavailable"));
        if(!row.id().equals(claim.snapshotId()) || !hash(row.referenceCode()).equals(row.referenceSha256())
                || !hash(row.solutionCode()).equals(row.solutionSha256())
                || row.referenceCode().isBlank() || row.solutionCode().isBlank()
                || row.referenceCode().getBytes(StandardCharsets.UTF_8).length>65536
                || row.solutionCode().getBytes(StandardCharsets.UTF_8).length>65536) throw invalid("Invalid program integrity");
        String actual=hash(framed(row.draftId(),Long.toString(row.ownerId()),Long.toString(row.draftVersion()),row.metadataText(),row.solutionIdea(),row.referenceSha256(),row.solutionSha256(),row.testDatasetSha256(),row.javaImageDigest(),row.comparisonRuleVersion(),row.sandboxPolicyVersion()));
        if(!actual.equals(row.snapshotSha256())) throw invalid("Invalid frozen content integrity");
        var metadata=json.readTree(row.metadataText());
        for(String field:List.of("timeLimitMs","memoryLimitMb","outputLimitBytes")) if(metadata.get(field)==null || !metadata.get(field).isIntegralNumber() || !metadata.get(field).canConvertToLong()) throw invalid("Invalid resource metadata");
        if(metadata.get("timeLimitMs").longValue()!=row.timeLimitMs() || metadata.get("memoryLimitMb").longValue()!=row.memoryLimitMb() || metadata.get("outputLimitBytes").longValue()!=row.outputLimitBytes()) throw invalid("Resource snapshot mismatch");
        List<JudgeTestCase> cases=new ArrayList<>();StringBuilder manifest=new StringBuilder();long total=0;
        var tests=mapper.tests(row.id());if(tests.isEmpty() || tests.size()>100) throw invalid("Invalid validation dataset count");
        for(var test:tests) {
            if(test.sequence()!=cases.size()+1) throw invalid("Invalid validation test order");
            byte[] input=decode(test.inputGzip(),test.inputBytes(),test.inputSha256()),output=decode(test.outputGzip(),test.outputBytes(),test.outputSha256());
            total+=input.length+output.length;if(total>16L*1024*1024) throw invalid("Validation dataset too large");
            cases.add(new JudgeTestCase(test.sequence(),input,output));
            manifest.append(test.sequence()).append(':').append(test.inputSha256()).append(':').append(test.outputSha256()).append('\n');
        }
        if(!hash(manifest.toString()).equals(row.testDatasetSha256())) throw invalid("Invalid dataset identity");
        // This is only the existing sandbox's execution DTO: job ID is an opaque run ID,
        // judgeVersionId=0 denotes no public judge version; no Submission is made or loaded.
        return new Programs(program(claim,row,row.referenceCode(),row.referenceSha256(),cases),program(claim,row,row.solutionCode(),row.solutionSha256(),cases));
    }
    private static JudgeTaskSnapshot program(ValidationLeaseService.Claim claim,ValidationMapper.Snapshot s,String code,String digest,List<JudgeTestCase> tests) {
        return new JudgeTaskSnapshot(claim.jobId(),claim.jobId(),0,"JAVA_21",code,digest,s.timeLimitMs(),s.memoryLimitMb(),s.outputLimitBytes(),s.comparisonRuleVersion(),s.sandboxPolicyVersion(),s.javaImageDigest(),s.testDatasetSha256(),tests);
    }
    private static byte[] decode(byte[] compressed,long expected,String digest) {
        if(compressed==null || compressed.length>2*1048576 || expected<0 || expected>1048576) throw invalid("Invalid validation file bound");
        try(var stream=new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] bytes=stream.readNBytes((int)expected+1);
            if(bytes.length!=expected || stream.read()!=-1 || !hash(bytes).equals(digest)) throw invalid("Invalid validation file integrity");
            return bytes;
        } catch(IOException e) {throw invalid("Invalid validation compressed file");}
    }
    static String framed(String... values) {var out=new StringBuilder();for(String v:values) out.append(v.getBytes(StandardCharsets.UTF_8).length).append(':').append(v);return out.toString();}
    static String hash(String value) {return hash(value.getBytes(StandardCharsets.UTF_8));}
    private static String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("Missing SHA-256");}}
    private static JudgeTaskSnapshotException invalid(String reason) {return new JudgeTaskSnapshotException(reason);}
}
