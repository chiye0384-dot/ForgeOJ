/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.selftest;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SelfTestLeaseService {
    public record Claim(String jobId,String snapshotId,String attemptId,String token,String worker) {}
    private final SelfTestMapper mapper;
    private final String worker;
    private final long seconds;
    public SelfTestLeaseService(SelfTestMapper mapper,@Value("${forgeoj.worker.instance-id:${random.uuid}}") String worker,
            @Value("${forgeoj.worker.lease-duration-seconds:30}") long seconds) {
        if(seconds<1) throw new IllegalArgumentException("Invalid self-test lease");this.mapper=mapper;this.worker=worker;this.seconds=seconds;
    }
    @Transactional
    public Claim claim(String id,String snapshot) {
        var row=mapper.lock(id).orElse(null);
        if(row==null || !row.snapshotId().equals(snapshot)) throw new IllegalArgumentException("Unknown self-test contract");
        if(!java.util.Set.of("QUEUED","WAITING_RETRY").contains(row.processingStatus()) || !row.due()) return null;
        if(mapper.quota(row.ownerId()).isEmpty()) throw new IllegalStateException("Missing self-test quota");
        if(mapper.otherRunning(row.ownerId(),id)>0) {
            one(mapper.retry(id,row.statusVersion(),row.processingStatus(),5,"QUOTA_DEFERRED"));
            enqueue(id,false);return null;
        }
        if(row.attemptCount()>=row.maxAttempts()) {one(mapper.retry(id,row.statusVersion(),"SYSTEM_ERROR",0,"ATTEMPT_LIMIT_EXHAUSTED"));enqueue(id,true);return null;}
        String token=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString();
        one(mapper.claim(id,row.statusVersion(),worker,token,seconds));one(mapper.attempt(attempt,id,token));
        return new Claim(id,snapshot,attempt,token,worker);
    }
    @Transactional public void renew(Claim claim) {
        one(mapper.heartbeat(claim.jobId(),claim.token(),claim.worker(),seconds));
        one(mapper.attemptHeartbeat(claim.attemptId(),claim.jobId(),claim.token()));
    }
    @Transactional public void finish(Claim claim,com.forgeoj.worker.sandbox.SandboxOutputPreview preview) {
        matching(claim);
        if(preview.outcome()==com.forgeoj.worker.sandbox.SandboxOutcome.WRONG_ANSWER) throw new IllegalStateException("Self-test cannot judge answers");
        String result=preview.outcome()==com.forgeoj.worker.sandbox.SandboxOutcome.ACCEPTED?"SUCCESS":preview.outcome().name();
        if("SUCCESS".equals(result)) {
            if(preview.outputs().size()!=1) throw new IllegalStateException("Missing self-test output");
            String text=preview.outputs().getFirst();byte[] data=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(data.length>1048576 || text.indexOf('\0')>=0) throw new IllegalStateException("Invalid self-test output");
            var compressed=new java.io.ByteArrayOutputStream();
            try(var gzip=new java.util.zip.GZIPOutputStream(compressed)){gzip.write(data);}catch(java.io.IOException e){throw new IllegalStateException("Output compression failed");}
            one(mapper.output(claim.jobId(),claim.snapshotId(),compressed.toByteArray(),data.length,SelfTestSnapshotLoader.hash(text)));
        }
        one(mapper.finish(claim.jobId(),claim.token(),result));
        one(mapper.closeAttempt(claim.jobId(),claim.token(),"SUCCEEDED",null));
    }
    @Transactional public void failure(Claim claim,boolean invalid) {
        var row=matching(claim);retry(row,invalid,invalid?"SNAPSHOT_INVALID":"PLATFORM_FAILURE","RETRYABLE_FAILURE");
    }
    @Transactional public void recover(String id) {
        var row=mapper.lock(id).orElse(null);
        if(row==null || !row.processingStatus().equals("RUNNING") || row.leaseValid()) return;
        retry(row,false,"LEASE_EXPIRED","LEASE_EXPIRED");
    }
    private SelfTestMapper.Job matching(Claim claim) {
        var row=mapper.lock(claim.jobId()).orElseThrow(()->new IllegalStateException("Missing self-test"));
        if(!row.processingStatus().equals("RUNNING") || !row.leaseValid() || !claim.token().equals(row.leaseToken())
                || !claim.worker().equals(row.leaseOwner()) || !claim.snapshotId().equals(row.snapshotId())
                || mapper.activeAttempt(claim.attemptId(),claim.jobId(),claim.token())!=1) throw new IllegalStateException("SelfTest lease lost");
        return row;
    }
    private void retry(SelfTestMapper.Job row,boolean invalid,String failure,String attemptStatus) {
        boolean exhausted=invalid || row.attemptCount()>=row.maxAttempts();
        one(mapper.closeAttempt(row.id(),row.leaseToken(),exhausted?"DEAD_LETTERED":attemptStatus,failure));
        one(mapper.retry(row.id(),row.statusVersion(),exhausted?"SYSTEM_ERROR":"WAITING_RETRY",Math.min(60,1L<<Math.min(6,row.attemptCount())),failure));
        enqueue(row.id(),exhausted);
    }
    private void enqueue(String id,boolean dead) {one(mapper.outbox(UUID.randomUUID().toString(),id,dead?"SELF_TEST_DEAD_LETTERED":"SELF_TEST_QUEUED"));}
    private static void one(int count) {if(count!=1) throw new IllegalStateException("SelfTest fenced update failed");}
}
