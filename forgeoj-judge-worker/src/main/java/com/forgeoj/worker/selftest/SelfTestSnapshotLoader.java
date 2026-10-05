/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.selftest;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import com.forgeoj.worker.snapshot.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SelfTestSnapshotLoader {
    private final SelfTestMapper mapper;
    public SelfTestSnapshotLoader(SelfTestMapper mapper){this.mapper=mapper;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public JudgeTaskSnapshot load(SelfTestLeaseService.Claim claim) {
        var s=mapper.snapshot(claim.jobId(),claim.token(),claim.attemptId()).orElseThrow(()->invalid("Self-test snapshot unavailable"));
        byte[] input=s.inputText().getBytes(StandardCharsets.UTF_8);
        if(!s.id().equals(claim.snapshotId()) || !"JAVA_21".equals(s.language()) || s.sourceCode().isBlank() || s.sourceCode().indexOf('\0')>=0 || s.inputText().indexOf('\0')>=0 || s.sourceCode().getBytes(StandardCharsets.UTF_8).length>65536 || input.length>1048576 || input.length!=s.inputBytes() || !hash(s.sourceCode()).equals(s.sourceSha256()) || !hash(s.inputText()).equals(s.inputSha256())) throw invalid("Invalid self-test payload integrity");
        String digest=hash(framed(Long.toString(s.ownerId()),Long.toString(s.problemId()),s.problemSlug(),Long.toString(s.judgeVersionId()),s.language(),s.sourceSha256(),s.inputSha256(),Long.toString(s.inputBytes()),Integer.toString(s.timeLimitMs()),Integer.toString(s.memoryLimitMb()),Long.toString(s.outputLimitBytes()),s.javaImageDigest(),s.comparisonRuleVersion(),s.sandboxPolicyVersion()));
        if(!digest.equals(s.snapshotSha256()) || s.outputLimitBytes()<1 || s.outputLimitBytes()>1048576) throw invalid("Invalid self-test resource integrity");
        String dataset=hash("1:"+s.inputSha256()+":"+hash("")+"\n");
        // Opaque run IDs only: no formal Submission or official dataset is loaded.
        return new JudgeTaskSnapshot(claim.jobId(),claim.jobId(),s.judgeVersionId(),s.language(),s.sourceCode(),s.sourceSha256(),s.timeLimitMs(),s.memoryLimitMb(),s.outputLimitBytes(),s.comparisonRuleVersion(),s.sandboxPolicyVersion(),s.javaImageDigest(),dataset,List.of(new JudgeTestCase(1,input,new byte[0])));
    }
    static String framed(String... values){var out=new StringBuilder();for(String value:values) out.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);return out.toString();}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException("Missing SHA-256");}}
    private static JudgeTaskSnapshotException invalid(String message){return new JudgeTaskSnapshotException(message);}
}
