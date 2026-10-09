/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** Metadata projections only. Neither diagnostics nor message payloads leave this mapper. */
@Mapper
public interface OperationsMapper {
    record Task(String kind,String id,Long ownerId,String submissionId,String snapshotId,String status,
            long version,int attemptCount,int maxAttempts,String failureCode,String createdAt,
            String startedAt,String finishedAt,String nextAttemptAt,boolean leaseExpired,String expiresAt,boolean executionRecoveryUsed) {}
    String TASKS="""
        (SELECT 'FORMAL' AS kind,j.id,s.user_id AS ownerId,j.submission_id AS submissionId,
          NULL AS snapshotId,j.task_status AS status,j.status_version AS version,j.attempt_count AS attemptCount,
          j.max_attempts AS maxAttempts,j.last_failure_code AS failureCode,CAST(j.created_at AS CHAR) AS createdAt,
          CAST(j.started_at AS CHAR) AS startedAt,CAST(j.finished_at AS CHAR) AS finishedAt,
          CAST(j.next_attempt_at AS CHAR) AS nextAttemptAt,
          (j.lease_expires_at IS NOT NULL AND j.lease_expires_at<=CURRENT_TIMESTAMP(6)) AS leaseExpired,
          NULL AS expiresAt,EXISTS(SELECT 1 FROM operations_recovery_request r WHERE r.recovery_scope='EXECUTION' AND r.task_kind='FORMAL' AND r.task_id=j.id) AS executionRecoveryUsed FROM judge_task j JOIN submission s ON s.id=j.submission_id
        UNION ALL
        SELECT j.execution_kind,j.id,j.owner_id,NULL,j.snapshot_id,j.processing_status,j.status_version,
          j.attempt_count,j.max_attempts,j.last_failure_code,CAST(j.created_at AS CHAR),CAST(j.started_at AS CHAR),
          CAST(j.finished_at AS CHAR),CAST(j.next_attempt_at AS CHAR),
          (j.lease_expires_at IS NOT NULL AND j.lease_expires_at<=CURRENT_TIMESTAMP(6)),NULL,EXISTS(SELECT 1 FROM operations_recovery_request r WHERE r.recovery_scope='EXECUTION' AND r.task_kind=j.execution_kind AND r.task_id=j.id)
          FROM content_validation_job j
        UNION ALL
        SELECT 'SELF_TEST',j.id,j.owner_id,NULL,j.snapshot_id,j.processing_status,j.status_version,
          j.attempt_count,j.max_attempts,j.last_failure_code,CAST(j.created_at AS CHAR),CAST(j.started_at AS CHAR),
          CAST(j.finished_at AS CHAR),CAST(j.next_attempt_at AS CHAR),
          (j.lease_expires_at IS NOT NULL AND j.lease_expires_at<=CURRENT_TIMESTAMP(6)),CAST(j.expires_at AS CHAR),EXISTS(SELECT 1 FROM operations_recovery_request r WHERE r.recovery_scope='EXECUTION' AND r.task_kind='SELF_TEST' AND r.task_id=j.id)
          FROM self_test_job j) t
        """;
    String FILTER="""
        WHERE (#{kind} IS NULL OR t.kind=#{kind}) AND (#{id} IS NULL OR t.id=#{id})
          AND (#{status} IS NULL OR t.status=#{status})
          AND (t.status IN ('SYSTEM_ERROR','DEAD_LETTER','RETRYING','WAITING_RETRY')
            OR t.status='RUNNING' AND t.leaseExpired
            OR EXISTS(SELECT 1 FROM outbox_event o WHERE o.aggregate_id=t.id
              AND o.failed_at IS NOT NULL AND o.published_at IS NULL))
        """;
    @Select("SELECT COUNT(*) FROM "+TASKS+FILTER)
    long count(@Param("kind") String kind,@Param("id") String id,@Param("status") String status);
    @Select("SELECT t.* FROM "+TASKS+FILTER+" ORDER BY t.createdAt DESC,t.kind,t.id LIMIT #{size} OFFSET #{offset}")
    List<Task> list(@Param("kind") String kind,@Param("id") String id,@Param("status") String status,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT t.* FROM "+TASKS+" WHERE t.kind=#{kind} AND t.id=#{id}")
    Optional<Task> task(@Param("kind") String kind,@Param("id") String id);
    record Attempt(String id,int number,String status,String failureCode,String startedAt,String heartbeatAt,String leaseExpiresAt,String finishedAt) {}
    String ATTEMPTS="""
        (SELECT 'FORMAL' AS kind,judge_task_id AS taskId,id,attempt_no AS number,attempt_status AS status,
          failure_code AS failureCode,CAST(started_at AS CHAR) AS startedAt,CAST(heartbeat_at AS CHAR) AS heartbeatAt,
          CAST(lease_expires_at AS CHAR) AS leaseExpiresAt,CAST(finished_at AS CHAR) AS finishedAt FROM judge_task_attempt
        UNION ALL SELECT 'CONTENT',job_id,id,attempt_no,attempt_status,failure_code,CAST(started_at AS CHAR),
          CAST(heartbeat_at AS CHAR),CAST(lease_expires_at AS CHAR),CAST(finished_at AS CHAR) FROM content_validation_attempt
        UNION ALL SELECT 'SELF_TEST',job_id,id,attempt_no,attempt_status,failure_code,CAST(started_at AS CHAR),
          CAST(heartbeat_at AS CHAR),CAST(lease_expires_at AS CHAR),CAST(finished_at AS CHAR) FROM self_test_attempt) a
        """;
    String ATTEMPT_FILTER=" WHERE a.taskId=#{id} AND a.kind=CASE WHEN #{kind} IN ('VALIDATE','OUTPUT_PREVIEW') THEN 'CONTENT' ELSE #{kind} END ";
    @Select("SELECT COUNT(*) FROM "+ATTEMPTS+ATTEMPT_FILTER)
    long attemptCount(@Param("kind") String kind,@Param("id") String id);
    @Select("SELECT id,number,status,failureCode,startedAt,heartbeatAt,leaseExpiresAt,finishedAt FROM "+ATTEMPTS+ATTEMPT_FILTER+" ORDER BY number DESC,id LIMIT #{size} OFFSET #{offset}")
    List<Attempt> attempts(@Param("kind") String kind,@Param("id") String id,@Param("size") int size,@Param("offset") long offset);
    record Event(String id,String type,int sequence,int publishAttempts,String errorCode,String nextAttemptAt,String lastAttemptAt,String failedAt,String publishedAt,boolean deliveryRecoveryUsed) {}
    String EVENT_FILTER=" WHERE aggregate_id=#{id} AND aggregate_type=CASE WHEN #{kind}='FORMAL' THEN 'JUDGE_TASK' WHEN #{kind}='SELF_TEST' THEN 'SELF_TEST' ELSE 'CONTENT_VALIDATION' END ";
    @Select("SELECT COUNT(*) FROM outbox_event"+EVENT_FILTER)
    long eventCount(@Param("kind") String kind,@Param("id") String id);
    @Select("SELECT id,event_type AS type,sequence_no AS sequence,publish_attempts AS publishAttempts,last_error_code AS errorCode,CAST(next_attempt_at AS CHAR) AS nextAttemptAt,CAST(last_attempt_at AS CHAR) AS lastAttemptAt,CAST(failed_at AS CHAR) AS failedAt,CAST(published_at AS CHAR) AS publishedAt,EXISTS(SELECT 1 FROM operations_recovery_request r WHERE r.recovery_scope='DELIVERY' AND r.target_id=outbox_event.id) AS deliveryRecoveryUsed FROM outbox_event"+EVENT_FILTER+" ORDER BY created_at DESC,id LIMIT #{size} OFFSET #{offset}")
    List<Event> events(@Param("kind") String kind,@Param("id") String id,@Param("size") int size,@Param("offset") long offset);
    record Recovery(String id,String scope,String eventId,String previousStatus,long previousVersion,int previousAttempts,int previousMaxAttempts,String previousFailureCode,String previousFinishedAt,long resultingVersion,String createdAt) {}
    @Select("SELECT COUNT(*) FROM operations_recovery_request WHERE task_kind=#{kind} AND task_id=#{id}") long recoveryCount(@Param("kind") String kind,@Param("id") String id);
    @Select("SELECT id,recovery_scope AS scope,event_id,previous_status,previous_version,previous_attempts,previous_max_attempts,previous_failure_code,CAST(previous_finished_at AS CHAR) AS previousFinishedAt,resulting_version,CAST(created_at AS CHAR) AS createdAt FROM operations_recovery_request WHERE task_kind=#{kind} AND task_id=#{id} ORDER BY created_at DESC,id LIMIT #{size} OFFSET #{offset}")
    List<Recovery> recoveries(@Param("kind") String kind,@Param("id") String id,@Param("size") int size,@Param("offset") long offset);
}
