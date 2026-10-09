/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OperationsRecoveryMapper {
    record State(String id,long ownerId,String binding,String status,long version,int attempts,int maxAttempts,
        int sequence,String failureCode,String finishedAt,boolean noLease,boolean payloadValid) {}
    @Select("SELECT j.id,s.user_id AS ownerId,j.submission_id AS binding,j.task_status AS status,j.status_version AS version,j.attempt_count AS attempts,j.max_attempts AS maxAttempts,j.attempt_count AS sequence,j.last_failure_code AS failureCode,CAST(j.finished_at AS CHAR) AS finishedAt,(j.lease_token IS NULL AND j.lease_owner IS NULL AND j.lease_expires_at IS NULL) AS noLease,TRUE AS payloadValid FROM judge_task j JOIN submission s ON s.id=j.submission_id WHERE j.id=#{id} AND j.task_type='JUDGE_SUBMISSION' AND j.contract_version=1 FOR UPDATE")
    Optional<State> formal(String id);
    @Select("SELECT id,owner_id AS ownerId,snapshot_id AS binding,processing_status AS status,status_version AS version,attempt_count AS attempts,max_attempts AS maxAttempts,delivery_sequence AS sequence,last_failure_code AS failureCode,CAST(finished_at AS CHAR) AS finishedAt,(lease_token IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL) AS noLease,TRUE AS payloadValid FROM content_validation_job WHERE id=#{id} AND execution_kind=#{kind} FOR UPDATE")
    Optional<State> content(@Param("kind") String kind,@Param("id") String id);
    @Select("SELECT id,owner_id AS ownerId,snapshot_id AS binding,processing_status AS status,status_version AS version,attempt_count AS attempts,max_attempts AS maxAttempts,delivery_sequence AS sequence,last_failure_code AS failureCode,CAST(finished_at AS CHAR) AS finishedAt,(lease_token IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL) AS noLease,(expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP(6)) AND EXISTS(SELECT 1 FROM self_test_payload p WHERE p.snapshot_id=self_test_job.snapshot_id) AS payloadValid FROM self_test_job WHERE id=#{id} FOR UPDATE")
    Optional<State> self(String id);
    @Select("SELECT status FROM user_account WHERE id=#{owner} FOR UPDATE") Optional<String> account(long owner);
    @Select("SELECT user_id FROM user_judge_quota_lock WHERE user_id=#{owner} FOR UPDATE") Optional<Long> quota(long owner);
    @Select("SELECT (SELECT COUNT(*) FROM submission WHERE user_id=#{owner} AND processing_status IN ('QUEUED','RETRYING'))+(SELECT COUNT(*) FROM content_validation_job WHERE owner_id=#{owner} AND processing_status='QUEUED')+(SELECT COUNT(*) FROM self_test_job WHERE owner_id=#{owner} AND processing_status='QUEUED')") int pending(long owner);
    @Select("SELECT id FROM assignment_policy_fence WHERE id=1 FOR UPDATE") int assignmentFence();
    record Basis(long problemId,String classroomId,String assignmentId) {}
    @Select("SELECT s.problem_id AS problemId,COALESCE(c.classroom_id,p.classroom_id) AS classroomId,a.assignment_id AS assignmentId FROM submission s JOIN problem p ON p.id=s.problem_id LEFT JOIN assignment_attempt a ON a.submission_id=s.id LEFT JOIN classroom_assignment c ON c.id=a.assignment_id WHERE s.id=#{binding}") Basis formalBasis(String binding);
    @Select("SELECT s.problem_id AS problemId,COALESCE(c.classroom_id,p.classroom_id) AS classroomId,a.assignment_id AS assignmentId FROM self_test_snapshot s JOIN problem p ON p.id=s.problem_id LEFT JOIN assignment_self_test a ON a.run_id=#{id} LEFT JOIN classroom_assignment c ON c.id=a.assignment_id WHERE s.id=#{binding}") Basis selfBasis(@Param("id") String id,@Param("binding") String binding);
    @Select("SELECT status FROM classroom WHERE id=#{room} FOR UPDATE") Optional<String> room(String room);
    @Select("SELECT EXISTS(SELECT 1 FROM classroom_member WHERE classroom_id=#{room} AND user_id=#{owner} AND status='ACTIVE')") boolean member(@Param("room") String room,@Param("owner") long owner);
    @Select("SELECT status FROM classroom_assignment WHERE id=#{id} FOR UPDATE") Optional<String> assignment(String id);
    @Select("SELECT EXISTS(SELECT 1 FROM assignment_participant WHERE assignment_id=#{id} AND user_id=#{owner})") boolean participant(@Param("id") String id,@Param("owner") long owner);
    @Select("SELECT status FROM problem WHERE id=#{id} FOR SHARE") Optional<String> problem(long id);
    @Select("SELECT EXISTS(SELECT 1 FROM public_problem_governance WHERE problem_id=#{id} AND data_invalid=TRUE)") boolean invalid(long id);
    record Draft(String id,long version,long snapshotVersion,String status) {}
    @Select("SELECT d.id,d.version,(SELECT draft_version FROM content_validation_snapshot WHERE id=#{binding}) AS snapshotVersion,d.status FROM authored_problem_draft d WHERE d.id=(SELECT draft_id FROM content_validation_snapshot WHERE id=#{binding}) AND d.owner_id=(SELECT owner_id FROM content_validation_snapshot WHERE id=#{binding}) FOR UPDATE") Optional<Draft> draft(String binding);
    record Review(String status,String latestJob,String snapshotId) {}
    @Select("SELECT review_status AS status,COALESCE(latest_recheck_id,validation_job_id) AS latestJob,snapshot_id FROM content_review WHERE draft_id=#{draft} ORDER BY review_no DESC LIMIT 1 FOR UPDATE") Optional<Review> review(String draft);
    @Select("SELECT processing_status='SYSTEM_ERROR' AND verdict IS NULL FROM submission WHERE id=#{binding} FOR UPDATE") boolean submissionFailed(String binding);
    @Select("SELECT COUNT(*) FROM judge_task_attempt WHERE judge_task_id=#{id} AND attempt_status='RUNNING'") int formalActive(String id);
    @Select("SELECT COUNT(*) FROM content_validation_attempt WHERE job_id=#{id} AND attempt_status='RUNNING'") int contentActive(String id);
    @Select("SELECT COUNT(*) FROM self_test_attempt WHERE job_id=#{id} AND attempt_status='RUNNING'") int selfActive(String id);
    @Select("SELECT COUNT(*) FROM "+OperationsMapper.ATTEMPTS+OperationsMapper.ATTEMPT_FILTER+" AND number=#{number} AND status IN ('RETRYABLE_FAILURE','LEASE_EXPIRED','DEAD_LETTERED') AND failureCode IN ('PLATFORM_FAILURE','LEASE_EXPIRED','ATTEMPT_LIMIT_EXHAUSTED') AND finishedAt IS NOT NULL")
    int retryableLast(@Param("kind") String kind,@Param("id") String id,@Param("number") int number);
    @Update("UPDATE judge_task SET task_status='QUEUED',status_version=status_version+1,max_attempts=max_attempts+1,finished_at=NULL,next_attempt_at=NULL WHERE id=#{s.id} AND status_version=#{s.version} AND task_status='DEAD_LETTER' AND attempt_count=max_attempts AND lease_token IS NULL") int requeueFormal(@Param("s") State s);
    @Update("UPDATE submission SET processing_status='QUEUED',status_version=status_version+1,finished_at=NULL WHERE id=#{id} AND processing_status='SYSTEM_ERROR' AND verdict IS NULL") int requeueSubmission(String id);
    @Update("UPDATE content_validation_job SET processing_status='QUEUED',status_version=status_version+1,max_attempts=max_attempts+1,delivery_sequence=delivery_sequence+1,finished_at=NULL,next_attempt_at=NULL WHERE id=#{s.id} AND status_version=#{s.version} AND processing_status='SYSTEM_ERROR' AND attempt_count=max_attempts AND lease_token IS NULL") int requeueContent(@Param("s") State s);
    @Update("UPDATE self_test_job SET processing_status='QUEUED',status_version=status_version+1,max_attempts=max_attempts+1,delivery_sequence=delivery_sequence+1,finished_at=NULL,expires_at=NULL,next_attempt_at=NULL WHERE id=#{s.id} AND status_version=#{s.version} AND processing_status='SYSTEM_ERROR' AND attempt_count=max_attempts AND lease_token IS NULL AND expires_at>CURRENT_TIMESTAMP(6)") int requeueSelf(@Param("s") State s);
    @Insert("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,contract_version,sequence_no,payload) VALUES(#{event},#{aggregate},#{s.id},#{type},1,#{sequence},CAST(#{payload} AS JSON))") int outbox(@Param("event") String event,@Param("aggregate") String aggregate,@Param("type") String type,@Param("sequence") int sequence,@Param("payload") String payload,@Param("s") State s);
    record Delivery(String id,String aggregateType,String taskId,String type,int contractVersion,int sequence,int attempts,String failureCode,String failedAt,boolean unpublished,String payload) {}
    @Select("SELECT id,aggregate_type AS aggregateType,aggregate_id AS taskId,event_type AS type,contract_version AS contractVersion,sequence_no AS sequence,publish_attempts AS attempts,last_error_code AS failureCode,CAST(failed_at AS CHAR) AS failedAt,(published_at IS NULL) AS unpublished,CAST(payload AS CHAR) AS payload FROM outbox_event WHERE id=#{id} FOR UPDATE") Optional<Delivery> delivery(String id);
    @Update("UPDATE outbox_event SET failed_at=NULL,next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND publish_attempts=#{attempts} AND failed_at IS NOT NULL AND published_at IS NULL") int rearm(@Param("id") String id,@Param("attempts") int attempts);
    record Receipt(String id,String scope,String kind,String taskId,String targetId,String eventId,long version,String createdAt) {}
    record Prior(String hash,Receipt receipt) {}
    record RequestRow(String requestSha256,String id,String scope,String kind,String taskId,String targetId,String eventId,long version,String createdAt) { Receipt receipt(){return new Receipt(id,scope,kind,taskId,targetId,eventId,version,createdAt);} }
    @Select("SELECT request_sha256,id,recovery_scope AS scope,task_kind AS kind,task_id,target_id,event_id,resulting_version AS version,CAST(created_at AS CHAR) AS createdAt FROM operations_recovery_request WHERE actor_admin_id=#{actor} AND client_request_id=#{request}") Optional<RequestRow> prior(@Param("actor") long actor,@Param("request") String request);
    @Select("SELECT COUNT(*) FROM operations_recovery_request WHERE recovery_scope=#{scope} AND task_kind=#{kind} AND target_id=#{target}") int used(@Param("scope") String scope,@Param("kind") String kind,@Param("target") String target);
    @Insert("INSERT INTO operations_recovery_request(id,actor_admin_id,client_request_id,request_sha256,recovery_scope,task_kind,task_id,target_id,event_id,previous_status,previous_version,previous_attempts,previous_max_attempts,previous_failure_code,previous_finished_at,resulting_version,reason) VALUES(#{receipt},#{actor},#{request},#{hash},#{scope},#{kind},#{s.id},#{target},#{event},#{s.status},#{s.version},#{attempts},#{s.maxAttempts},#{failure},#{finished},#{version},#{reason})")
    int receipt(@Param("receipt") String receipt,@Param("actor") long actor,@Param("request") String request,@Param("hash") String hash,@Param("scope") String scope,@Param("kind") String kind,@Param("s") State s,@Param("target") String target,@Param("event") String event,@Param("attempts") int attempts,@Param("failure") String failure,@Param("finished") String finished,@Param("version") long version,@Param("reason") String reason);
}
