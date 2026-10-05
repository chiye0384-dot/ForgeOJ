/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ContentOutputMapper {
    record Preview(String jobId,String draftId,long draftVersion,String processingStatus,long statusVersion,String referenceResult,boolean stale,Long acceptedVersion) {}
    record OutputRow(int sequence,byte[] inputGzip,long inputBytes,String inputSha256,byte[] oldGzip,long oldBytes,String oldSha256,byte[] outputGzip,Long outputBytes,String outputSha256) {}
    record Case(int sequence,String input,String previousOutput,String generatedOutput) {}
    record Detail(Preview preview,List<Case> cases) {}
    record Page(List<Preview> items,int page,int size,long total) {}
    record Receipt(String jobId,String draftId,long expectedVersion,long appliedVersion) {}
    String COLUMNS="j.id AS jobId,s.draft_id AS draftId,s.draft_version AS draftVersion,IF(j.processing_status='WAITING_RETRY','RUNNING',j.processing_status) AS processingStatus,j.status_version AS statusVersion,j.reference_result AS referenceResult,(d.version<>s.draft_version OR d.status<>'DRAFT' OR EXISTS(SELECT 1 FROM content_review r WHERE r.active_draft_id=d.id)) AS stale,a.applied_version AS acceptedVersion";
    String FROM=" FROM content_validation_job j JOIN content_validation_snapshot s ON s.id=j.snapshot_id JOIN authored_problem_draft d ON d.id=s.draft_id LEFT JOIN content_output_acceptance a ON a.job_id=j.id ";
    @Select("SELECT "+COLUMNS+FROM+"WHERE j.owner_id=#{owner} AND s.draft_id=#{draft} AND j.id=#{job} AND j.execution_kind='OUTPUT_PREVIEW'")
    Optional<Preview> result(@Param("owner") long owner,@Param("draft") String draft,@Param("job") String job);
    @Select("SELECT "+COLUMNS+FROM+"WHERE j.owner_id=#{owner} AND s.draft_id=#{draft} AND j.id=#{job} AND j.execution_kind='OUTPUT_PREVIEW'")
    Optional<Preview> lock(@Param("owner") long owner,@Param("draft") String draft,@Param("job") String job);
    @Select("SELECT COUNT(*)"+FROM+"WHERE j.owner_id=#{owner} AND s.draft_id=#{draft} AND j.execution_kind='OUTPUT_PREVIEW'")
    long count(@Param("owner") long owner,@Param("draft") String draft);
    @Select("SELECT "+COLUMNS+FROM+"WHERE j.owner_id=#{owner} AND s.draft_id=#{draft} AND j.execution_kind='OUTPUT_PREVIEW' ORDER BY j.created_at DESC,j.id DESC LIMIT #{size} OFFSET #{offset}")
    List<Preview> list(@Param("owner") long owner,@Param("draft") String draft,@Param("size") int size,@Param("offset") long offset);
    @Select("""
        SELECT t.sequence_no AS sequence,t.input_gzip AS inputGzip,t.input_bytes AS inputBytes,t.input_sha256 AS inputSha256,
          t.expected_output_gzip AS oldGzip,t.expected_output_bytes AS oldBytes,t.expected_output_sha256 AS oldSha256,
          o.output_gzip AS outputGzip,o.output_bytes AS outputBytes,o.output_sha256 AS outputSha256
        FROM content_validation_job j JOIN content_validation_snapshot s ON s.id=j.snapshot_id
          JOIN content_validation_test_case t ON t.snapshot_id=s.id LEFT JOIN content_output_preview_case o ON o.job_id=j.id AND o.sequence_no=t.sequence_no
        WHERE j.id=#{job} AND j.owner_id=#{owner} AND s.draft_id=#{draft} AND j.execution_kind='OUTPUT_PREVIEW' ORDER BY t.sequence_no
        """)
    List<OutputRow> outputs(@Param("owner") long owner,@Param("draft") String draft,@Param("job") String job);
    @Select("SELECT job_id AS jobId,draft_id AS draftId,expected_version AS expectedVersion,applied_version AS appliedVersion FROM content_output_acceptance WHERE job_id=#{job} AND owner_id=#{owner} AND draft_id=#{draft}")
    Optional<Receipt> receipt(@Param("owner") long owner,@Param("draft") String draft,@Param("job") String job);
    @Insert("INSERT INTO content_output_acceptance(job_id,draft_id,owner_id,expected_version,applied_version) VALUES(#{job},#{draft},#{owner},#{version},#{version}+1)")
    int accept(@Param("owner") long owner,@Param("draft") String draft,@Param("job") String job,@Param("version") long version);
}
