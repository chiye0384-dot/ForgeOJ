/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.selftest;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SelfTestMapper {
    @Select("SELECT problem_id AS problemId,id AS judgeVersionId,time_limit_ms,memory_limit_mb,output_limit_bytes,java_image_digest,comparison_rule_version,sandbox_policy_version FROM problem_judge_version WHERE problem_id=#{problem} AND id=#{judge}") Optional<Version> frozenVersion(@Param("problem") long problem,@Param("judge") long judge);
    @Select("SELECT EXISTS(SELECT 1 FROM self_test_job j JOIN self_test_snapshot s ON s.id=j.snapshot_id WHERE j.owner_id=#{owner} AND j.client_request_id=#{request} AND s.judge_version_id=#{judge})") boolean requestBasis(@Param("owner") long owner,@Param("request") String request,@Param("judge") long judge);
    @Select("SELECT p.id AS problemId,v.id AS judgeVersionId,v.time_limit_ms,v.memory_limit_mb,v.output_limit_bytes,v.java_image_digest,v.comparison_rule_version,v.sandbox_policy_version FROM problem p JOIN problem_judge_version v ON v.id=p.current_judge_version_id JOIN classroom_problem c ON c.problem_id=p.id AND c.classroom_id=p.classroom_id WHERE p.classroom_id=#{room} AND p.scope='CLASSROOM' AND p.slug=#{slug} AND p.status='ACTIVE' FOR SHARE")
    Optional<Version> classroomVersion(@Param("room") String room,@Param("slug") String slug);
    record Version(long problemId,long judgeVersionId,int timeLimitMs,int memoryLimitMb,long outputLimitBytes,String javaImageDigest,String comparisonRuleVersion,String sandboxPolicyVersion) {}
    record Snapshot(String id,long ownerId,long problemId,String problemSlug,long judgeVersionId,String language,String sourceSha256,String inputSha256,long inputBytes,int timeLimitMs,int memoryLimitMb,long outputLimitBytes,String javaImageDigest,String comparisonRuleVersion,String sandboxPolicyVersion,String snapshotSha256) {}
    record Run(String runId,String problemSlug,long judgeVersion,String processingStatus,long statusVersion,String executionResult,String expiresAt) {}
    record Request(String runId,String problemSlug,String language,String sourceSha256,String inputSha256,boolean expired) {}
    record Payload(String sourceCode,String input,String sourceSha256,String inputSha256,long inputBytes) {}
    record Output(byte[] gzip,long bytes,String sha256) {}
    record Detail(Run run,String sourceCode,String input,String output) {}
    record Page(List<Run> items,int page,int size,long total) {}
    String FROM=" FROM self_test_job j JOIN self_test_snapshot s ON s.id=j.snapshot_id JOIN problem_judge_version v ON v.id=s.judge_version_id ";
    String FIELDS="j.id AS runId,s.problem_slug AS problemSlug,v.version_no AS judgeVersion,IF(j.processing_status='WAITING_RETRY','RUNNING',j.processing_status) AS processingStatus,j.status_version AS statusVersion,j.execution_result AS executionResult,CAST(j.expires_at AS CHAR) AS expiresAt";
    String LIVE=" AND (j.expires_at IS NULL OR j.expires_at>CURRENT_TIMESTAMP(6)) ";
    @Select("SELECT user_id FROM user_judge_quota_lock WHERE user_id=#{owner} FOR UPDATE") Optional<Long> quota(long owner);
    @Select("SELECT (SELECT COUNT(*) FROM submission WHERE user_id=#{owner} AND processing_status IN ('QUEUED','RETRYING'))+(SELECT COUNT(*) FROM content_validation_job WHERE owner_id=#{owner} AND processing_status='QUEUED')+(SELECT COUNT(*) FROM self_test_job WHERE owner_id=#{owner} AND processing_status='QUEUED')") int pending(long owner);
    @Select("SELECT j.id AS runId,s.problem_slug AS problemSlug,s.language,s.source_sha256,s.input_sha256,(j.expires_at IS NOT NULL AND j.expires_at<=CURRENT_TIMESTAMP(6)) AS expired"+FROM+"WHERE j.owner_id=#{owner} AND j.client_request_id=#{request}")
    Optional<Request> request(@Param("owner") long owner,@Param("request") String request);
    @Select("SELECT p.id AS problemId,v.id AS judgeVersionId,v.time_limit_ms,v.memory_limit_mb,v.output_limit_bytes,v.java_image_digest,v.comparison_rule_version,v.sandbox_policy_version FROM problem p JOIN problem_judge_version v ON v.id=p.current_judge_version_id WHERE p.slug=#{slug} AND p.status='ACTIVE' AND p.scope='PUBLIC'") Optional<Version> version(String slug);
    @Insert("INSERT INTO self_test_snapshot(id,owner_id,problem_id,problem_slug,judge_version_id,language,source_sha256,input_sha256,input_bytes,time_limit_ms,memory_limit_mb,output_limit_bytes,java_image_digest,comparison_rule_version,sandbox_policy_version,snapshot_sha256) VALUES(#{id},#{ownerId},#{problemId},#{problemSlug},#{judgeVersionId},#{language},#{sourceSha256},#{inputSha256},#{inputBytes},#{timeLimitMs},#{memoryLimitMb},#{outputLimitBytes},#{javaImageDigest},#{comparisonRuleVersion},#{sandboxPolicyVersion},#{snapshotSha256})") int snapshot(Snapshot row);
    @Insert("INSERT INTO self_test_payload(snapshot_id,source_code,input_text) VALUES(#{id},#{code},#{input})") int insertPayload(@Param("id") String id,@Param("code") String code,@Param("input") String input);
    @Insert("INSERT INTO self_test_job(id,snapshot_id,owner_id,client_request_id) VALUES(#{id},#{snapshot},#{owner},#{request})") int job(@Param("id") String id,@Param("snapshot") String snapshot,@Param("owner") long owner,@Param("request") String request);
    @Insert("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,contract_version,payload) VALUES(#{event},'SELF_TEST',#{id},'SELF_TEST_QUEUED',1,CAST(#{payload} AS JSON))") int outbox(@Param("event") String event,@Param("id") String id,@Param("payload") String payload);
    @Select("SELECT "+FIELDS+FROM+"WHERE j.owner_id=#{owner} AND j.id=#{id}"+LIVE) Optional<Run> run(@Param("owner") long owner,@Param("id") String id);
    @Select("SELECT p.source_code,p.input_text AS input,s.source_sha256,s.input_sha256,s.input_bytes FROM self_test_payload p JOIN self_test_job j ON j.snapshot_id=p.snapshot_id JOIN self_test_snapshot s ON s.id=p.snapshot_id WHERE j.owner_id=#{owner} AND j.id=#{id}"+LIVE) Optional<Payload> payload(@Param("owner") long owner,@Param("id") String id);
    @Select("SELECT output_gzip AS gzip,output_bytes AS bytes,output_sha256 AS sha256 FROM self_test_output WHERE job_id=#{id}") Optional<Output> output(String id);
    @Select("SELECT COUNT(*)"+FROM+"WHERE j.owner_id=#{owner} AND s.problem_slug=#{slug}"+LIVE) long count(@Param("owner") long owner,@Param("slug") String slug);
    @Select("SELECT "+FIELDS+FROM+"WHERE j.owner_id=#{owner} AND s.problem_slug=#{slug}"+LIVE+"ORDER BY j.created_at DESC,j.id DESC LIMIT #{size} OFFSET #{offset}") List<Run> list(@Param("owner") long owner,@Param("slug") String slug,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT j.id FROM self_test_job j WHERE j.owner_id=#{owner} AND j.id=#{id}"+LIVE+"FOR UPDATE") Optional<String> lock(@Param("owner") long owner,@Param("id") String id);
    @Update("UPDATE self_test_job SET processing_status='CANCELLED',status_version=status_version+1,finished_at=CURRENT_TIMESTAMP(6),expires_at=TIMESTAMPADD(HOUR,24,CURRENT_TIMESTAMP(6)),next_attempt_at=NULL WHERE owner_id=#{owner} AND id=#{id} AND processing_status='QUEUED'") int cancel(@Param("owner") long owner,@Param("id") String id);
    @Select("SELECT j.id FROM self_test_job j WHERE j.processing_status IN ('FINISHED','CANCELLED','SYSTEM_ERROR') AND j.expires_at<=CURRENT_TIMESTAMP(6) AND (EXISTS(SELECT 1 FROM self_test_payload p WHERE p.snapshot_id=j.snapshot_id) OR EXISTS(SELECT 1 FROM self_test_output o WHERE o.job_id=j.id)) ORDER BY j.expires_at,j.id LIMIT 20") List<String> expired();
    @Delete("DELETE p FROM self_test_payload p JOIN self_test_job j ON j.snapshot_id=p.snapshot_id WHERE j.id=#{id} AND j.processing_status IN ('FINISHED','CANCELLED','SYSTEM_ERROR') AND j.expires_at<=CURRENT_TIMESTAMP(6)") int purgePayload(String id);
    @Delete("DELETE o FROM self_test_output o JOIN self_test_job j ON j.id=o.job_id WHERE j.id=#{id} AND j.processing_status IN ('FINISHED','CANCELLED','SYSTEM_ERROR') AND j.expires_at<=CURRENT_TIMESTAMP(6)") int purgeOutput(String id);
}
