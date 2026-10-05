/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ContentValidationMapper {
    @Select("SELECT execution_kind FROM content_validation_job WHERE owner_id=#{owner} AND id=#{job}")
    String kind(@Param("owner") long owner,@Param("job") String job);
    record Result(String jobId,String draftId,long draftVersion,String processingStatus,long statusVersion,
            String validationStatus,String referenceResult,String solutionResult,boolean stale) {}
    record Page(java.util.List<Result> items,int page,int size,long total) {}
    record Snapshot(String id,String draftId,long ownerId,long draftVersion,String metadataText,
            String referenceCode,String solutionIdea,String solutionCode,String referenceSha256,
            String solutionSha256,String testDatasetSha256,String snapshotSha256,String image,
            int timeLimitMs,int memoryLimitMb,long outputLimitBytes) {}
    String RESULT="j.id AS jobId,s.draft_id AS draftId,s.draft_version AS draftVersion,IF(j.processing_status='WAITING_RETRY','RUNNING',j.processing_status) AS processingStatus,j.status_version AS statusVersion,j.validation_status AS validationStatus,j.reference_result AS referenceResult,j.solution_result AS solutionResult,(d.version<>s.draft_version OR d.status<>'DRAFT') AS stale";
    String FROM=" FROM content_validation_job j JOIN content_validation_snapshot s ON s.id=j.snapshot_id JOIN authored_problem_draft d ON d.id=s.draft_id ";
    @Insert("""
        INSERT INTO content_validation_snapshot(id,draft_id,owner_id,draft_version,metadata_text,
          reference_code,solution_idea,solution_code,reference_sha256,solution_sha256,test_dataset_sha256,
          snapshot_sha256,java_image_digest,time_limit_ms,memory_limit_mb,output_limit_bytes,execution_kind)
        VALUES(#{id},#{draftId},#{ownerId},#{draftVersion},#{metadataText},#{referenceCode},#{solutionIdea},
          #{solutionCode},#{referenceSha256},#{solutionSha256},#{testDatasetSha256},#{snapshotSha256},
          #{image},#{timeLimitMs},#{memoryLimitMb},#{outputLimitBytes},'OUTPUT_PREVIEW')
        """)
    int insertPreviewSnapshot(Snapshot snapshot);
    @Insert("INSERT INTO content_validation_job(id,snapshot_id,owner_id,client_request_id,execution_kind) VALUES(#{job},#{snapshot},#{owner},#{request},'OUTPUT_PREVIEW')")
    int previewJob(@Param("job") String job,@Param("snapshot") String snapshot,@Param("owner") long owner,@Param("request") String request);
    @Select("SELECT execution_kind FROM content_validation_job WHERE owner_id=#{owner} AND client_request_id=#{request}")
    String requestKind(@Param("owner") long owner,@Param("request") String request);
    @Select("SELECT user_id FROM user_judge_quota_lock WHERE user_id=#{owner} FOR UPDATE")
    Optional<Long> lockQuota(long owner);
    @Select("SELECT (SELECT COUNT(*) FROM submission WHERE user_id=#{owner} AND processing_status IN ('QUEUED','RETRYING'))+(SELECT COUNT(*) FROM content_validation_job WHERE owner_id=#{owner} AND processing_status='QUEUED')+(SELECT COUNT(*) FROM self_test_job WHERE owner_id=#{owner} AND processing_status='QUEUED')")
    int pending(long owner);
    @Select("SELECT "+RESULT+FROM+"WHERE j.owner_id=#{owner} AND j.client_request_id=#{request}")
    Optional<Result> request(@Param("owner") long owner,@Param("request") String request);
    @Select("SELECT "+RESULT+FROM+"WHERE j.owner_id=#{owner} AND s.draft_id=#{draft} AND j.id=#{job}")
    Optional<Result> result(@Param("owner") long owner,@Param("draft") String draft,@Param("job") String job);
    @Select("SELECT COUNT(*)"+FROM+"WHERE j.owner_id=#{owner} AND s.draft_id=#{draft} AND j.execution_kind='VALIDATE'")
    long count(@Param("owner") long owner,@Param("draft") String draft);
    @Select("SELECT "+RESULT+FROM+"WHERE j.owner_id=#{owner} AND s.draft_id=#{draft} AND j.execution_kind='VALIDATE' ORDER BY j.created_at DESC,j.id DESC LIMIT #{size} OFFSET #{offset}")
    java.util.List<Result> list(@Param("owner") long owner,@Param("draft") String draft,@Param("size") int size,@Param("offset") long offset);
    @Insert("""
        INSERT INTO content_validation_snapshot(id,draft_id,owner_id,draft_version,metadata_text,
          reference_code,solution_idea,solution_code,reference_sha256,solution_sha256,test_dataset_sha256,
          snapshot_sha256,java_image_digest,time_limit_ms,memory_limit_mb,output_limit_bytes)
        VALUES(#{id},#{draftId},#{ownerId},#{draftVersion},#{metadataText},#{referenceCode},#{solutionIdea},
          #{solutionCode},#{referenceSha256},#{solutionSha256},#{testDatasetSha256},#{snapshotSha256},
          #{image},#{timeLimitMs},#{memoryLimitMb},#{outputLimitBytes})
        """)
    int snapshot(Snapshot snapshot);
    @Insert("""
        INSERT INTO content_validation_test_case(snapshot_id,sequence_no,input_gzip,expected_output_gzip,
          input_bytes,expected_output_bytes,input_sha256,expected_output_sha256)
        SELECT #{snapshot},t.sequence_no,t.input_gzip,t.expected_output_gzip,t.input_bytes,
          t.expected_output_bytes,t.input_sha256,t.expected_output_sha256
        FROM authored_problem_test_case t JOIN authored_problem_draft d ON d.id=t.draft_id
        WHERE d.id=#{draft} AND d.owner_id=#{owner} ORDER BY t.sequence_no
        """)
    int copyTests(@Param("owner") long owner,@Param("draft") String draft,@Param("snapshot") String snapshot);
    @Insert("INSERT INTO content_validation_job(id,snapshot_id,owner_id,client_request_id) VALUES(#{job},#{snapshot},#{owner},#{request})")
    int job(@Param("job") String job,@Param("snapshot") String snapshot,@Param("owner") long owner,@Param("request") String request);
    @Insert("""
        INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,contract_version,payload)
        VALUES(#{event},'CONTENT_VALIDATION',#{job},'CONTENT_VALIDATION_QUEUED',1,CAST(#{payload} AS JSON))
        """)
    int outbox(@Param("event") String event,@Param("job") String job,@Param("payload") String payload);
}
