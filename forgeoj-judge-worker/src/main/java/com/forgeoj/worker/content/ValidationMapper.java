/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.content;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ValidationMapper {
    record Job(String id,String snapshotId,long ownerId,String processingStatus,long statusVersion,int attemptCount,
            int maxAttempts,int deliverySequence,String leaseOwner,String leaseToken,boolean leaseValid,boolean due) {}
    record Snapshot(String id,String draftId,long ownerId,long draftVersion,String metadataText,String referenceCode,
            String solutionIdea,String solutionCode,String referenceSha256,String solutionSha256,String testDatasetSha256,
            String snapshotSha256,String javaImageDigest,int timeLimitMs,int memoryLimitMb,long outputLimitBytes,
            String comparisonRuleVersion,String sandboxPolicyVersion) {}
    record TestRow(int sequence,byte[] inputGzip,byte[] outputGzip,long inputBytes,long outputBytes,String inputSha256,String outputSha256) {}
    @Select("SELECT id,snapshot_id,owner_id,processing_status,status_version,attempt_count,max_attempts,delivery_sequence,lease_owner,lease_token,(lease_expires_at IS NOT NULL AND lease_expires_at>CURRENT_TIMESTAMP(6)) AS leaseValid,(next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP(6)) AS due FROM content_validation_job WHERE id=#{id} FOR UPDATE")
    Optional<Job> lock(String id);
    @Select("SELECT user_id FROM user_judge_quota_lock WHERE user_id=#{owner} FOR UPDATE")
    Optional<Long> quota(long owner);
    @Select("SELECT (SELECT COUNT(*) FROM submission WHERE user_id=#{owner} AND processing_status='RUNNING')+(SELECT COUNT(*) FROM content_validation_job WHERE owner_id=#{owner} AND id<>#{id} AND processing_status IN ('RUNNING','WAITING_RETRY'))")
    int otherRunning(@Param("owner") long owner,@Param("id") String id);
    @Update("""
        UPDATE content_validation_job SET processing_status='RUNNING',status_version=status_version+1,
          attempt_count=attempt_count+1,lease_owner=#{worker},lease_token=#{token},
          lease_expires_at=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(6)),next_attempt_at=NULL,
          started_at=COALESCE(started_at,CURRENT_TIMESTAMP(6)),last_failure_code=NULL
        WHERE id=#{id} AND status_version=#{version} AND processing_status IN ('QUEUED','WAITING_RETRY')
          AND attempt_count<max_attempts AND (next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP(6))
        """)
    int claim(@Param("id") String id,@Param("version") long version,@Param("worker") String worker,@Param("token") String token,@Param("seconds") long seconds);
    @Insert("INSERT INTO content_validation_attempt(id,job_id,attempt_no,lease_token,worker_id,lease_expires_at) SELECT #{attempt},id,attempt_count,lease_token,lease_owner,lease_expires_at FROM content_validation_job WHERE id=#{id} AND lease_token=#{token} AND processing_status='RUNNING' AND lease_expires_at>CURRENT_TIMESTAMP(6)")
    int attempt(@Param("attempt") String attempt,@Param("id") String id,@Param("token") String token);
    @Update("UPDATE content_validation_job SET lease_expires_at=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(6)) WHERE id=#{id} AND processing_status='RUNNING' AND lease_token=#{token} AND lease_owner=#{worker} AND lease_expires_at>CURRENT_TIMESTAMP(6)")
    int heartbeat(@Param("id") String id,@Param("token") String token,@Param("worker") String worker,@Param("seconds") long seconds);
    @Update("UPDATE content_validation_attempt a JOIN content_validation_job j ON j.id=a.job_id SET a.heartbeat_at=CURRENT_TIMESTAMP(6),a.lease_expires_at=j.lease_expires_at WHERE a.id=#{attempt} AND a.job_id=#{id} AND a.lease_token=#{token} AND a.attempt_status='RUNNING' AND j.lease_token=#{token} AND j.processing_status='RUNNING' AND j.lease_expires_at>CURRENT_TIMESTAMP(6)")
    int attemptHeartbeat(@Param("attempt") String attempt,@Param("id") String id,@Param("token") String token);
    @Select("SELECT COUNT(*) FROM content_validation_attempt WHERE id=#{attempt} AND job_id=#{id} AND lease_token=#{token} AND attempt_status='RUNNING' AND lease_expires_at>CURRENT_TIMESTAMP(6)")
    int activeAttempt(@Param("attempt") String attempt,@Param("id") String id,@Param("token") String token);
    @Update("UPDATE content_validation_attempt SET attempt_status=#{status},failure_code=#{failure},finished_at=CURRENT_TIMESTAMP(6) WHERE job_id=#{id} AND lease_token=#{token} AND attempt_status='RUNNING'")
    int closeAttempt(@Param("id") String id,@Param("token") String token,@Param("status") String status,@Param("failure") String failure);
    @Update("""
        UPDATE content_validation_job SET processing_status='FINISHED',status_version=status_version+1,
          reference_result=#{reference},solution_result=#{solution},
          validation_status=IF(#{reference}='ACCEPTED' AND #{solution}='ACCEPTED','PASSED','FAILED'),
          lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,next_attempt_at=NULL,finished_at=CURRENT_TIMESTAMP(6)
        WHERE id=#{id} AND processing_status='RUNNING' AND lease_token=#{token}
          AND lease_expires_at>CURRENT_TIMESTAMP(6)
        """)
    int finish(@Param("id") String id,@Param("token") String token,@Param("reference") String reference,@Param("solution") String solution);
    @Update("""
        UPDATE content_validation_job SET processing_status=#{status},status_version=status_version+1,
          lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,last_failure_code=#{failure},
          next_attempt_at=IF(#{status}='SYSTEM_ERROR',NULL,TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(6))),
          finished_at=IF(#{status}='SYSTEM_ERROR',CURRENT_TIMESTAMP(6),NULL),delivery_sequence=delivery_sequence+1
        WHERE id=#{id} AND status_version=#{version}
        """)
    int retry(@Param("id") String id,@Param("version") long version,@Param("status") String status,@Param("delay") long delay,@Param("failure") String failure);
    @Insert("""
        INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,contract_version,sequence_no,payload,next_attempt_at)
        SELECT #{event},'CONTENT_VALIDATION',id,#{type},1,delivery_sequence,
          JSON_OBJECT('taskId',id,'snapshotId',snapshot_id,'taskType','CONTENT_VALIDATE','contractVersion',1),
          COALESCE(next_attempt_at,CURRENT_TIMESTAMP(6)) FROM content_validation_job WHERE id=#{id}
        """)
    int outbox(@Param("event") String event,@Param("id") String id,@Param("type") String type);
    @Select("SELECT id FROM content_validation_job WHERE processing_status='RUNNING' AND lease_expires_at<=CURRENT_TIMESTAMP(6) ORDER BY lease_expires_at,id LIMIT 20")
    List<String> expired();
    @Select("""
        SELECT s.id,s.draft_id,s.owner_id,s.draft_version,s.metadata_text,s.reference_code,s.solution_idea,
          s.solution_code,s.reference_sha256,s.solution_sha256,s.test_dataset_sha256,s.snapshot_sha256,
          s.java_image_digest,s.time_limit_ms,s.memory_limit_mb,s.output_limit_bytes,
          s.comparison_rule_version,s.sandbox_policy_version
        FROM content_validation_snapshot s JOIN content_validation_job j ON j.snapshot_id=s.id
          JOIN content_validation_attempt a ON a.job_id=j.id
        WHERE j.id=#{id} AND j.processing_status='RUNNING' AND j.lease_token=#{token}
          AND j.lease_expires_at>CURRENT_TIMESTAMP(6) AND a.id=#{attempt} AND a.lease_token=#{token}
          AND a.attempt_status='RUNNING' AND a.lease_expires_at>CURRENT_TIMESTAMP(6)
        """)
    Optional<Snapshot> snapshot(@Param("id") String id,@Param("token") String token,@Param("attempt") String attempt);
    @Select("SELECT sequence_no AS sequence,input_gzip AS inputGzip,expected_output_gzip AS outputGzip,input_bytes AS inputBytes,expected_output_bytes AS outputBytes,input_sha256 AS inputSha256,expected_output_sha256 AS outputSha256 FROM content_validation_test_case WHERE snapshot_id=#{id} ORDER BY sequence_no")
    List<TestRow> tests(String id);
}
