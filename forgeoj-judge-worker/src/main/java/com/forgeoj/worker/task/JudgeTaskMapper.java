package com.forgeoj.worker.task;

import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface JudgeTaskMapper {

    @Select(
            """
            SELECT jt.id AS taskId,
                   jt.submission_id AS submissionId,
                   s.user_id AS userId,
                   jt.task_type AS taskType,
                   jt.contract_version AS contractVersion,
                   jt.task_status AS taskStatus,
                   jt.status_version AS taskStatusVersion,
                   jt.attempt_count AS attemptCount,
                   jt.max_attempts AS maxAttempts,
                   jt.lease_token AS leaseToken,
                   (jt.lease_expires_at IS NOT NULL
                       AND jt.lease_expires_at <= CURRENT_TIMESTAMP(6)) AS leaseExpired,
                   (jt.next_attempt_at IS NULL
                       OR jt.next_attempt_at <= CURRENT_TIMESTAMP(6)) AS retryDue,
                   s.processing_status AS submissionStatus,
                   s.status_version AS submissionStatusVersion
            FROM judge_task jt
            JOIN submission s ON s.id = jt.submission_id
            WHERE jt.id = #{taskId}
            FOR UPDATE
            """)
    Optional<JudgeTaskClaimRow> findForUpdate(@Param("taskId") String taskId);

    @Select(
            """
            SELECT user_id
            FROM user_judge_quota_lock
            WHERE user_id = #{userId}
            FOR UPDATE
            """)
    Optional<Long> lockUserQuota(@Param("userId") long userId);

    @Select(
            """
            SELECT (SELECT COUNT(*) FROM submission
            WHERE user_id = #{userId}
              AND processing_status = 'RUNNING'
              AND id <> #{submissionId})
              + (SELECT COUNT(*) FROM content_validation_job WHERE owner_id=#{userId}
                   AND processing_status IN ('RUNNING','WAITING_RETRY'))
            """)
    int countOtherRunningSubmissions(
            @Param("userId") long userId, @Param("submissionId") String submissionId);

    @Select(
            """
            SELECT (SELECT COUNT(*) FROM submission
            WHERE user_id = #{userId} AND processing_status IN ('QUEUED', 'RETRYING'))
            + (SELECT COUNT(*) FROM content_validation_job WHERE owner_id=#{userId} AND processing_status='QUEUED')
            """)
    int countQueuedOrRetrying(@Param("userId") long userId);

    @Update(
            """
            UPDATE judge_task
            SET next_attempt_at = TIMESTAMPADD(
                    SECOND, #{delaySeconds}, CURRENT_TIMESTAMP(6)
                )
            WHERE id = #{taskId}
              AND submission_id = #{submissionId}
              AND task_status = #{expectedStatus}
              AND status_version = #{statusVersion}
            """)
    int deferForUserQuota(
            @Param("taskId") String taskId,
            @Param("submissionId") String submissionId,
            @Param("expectedStatus") String expectedStatus,
            @Param("statusVersion") long statusVersion,
            @Param("delaySeconds") long delaySeconds);

    @Update(
            """
            UPDATE judge_task
            SET task_status = 'RUNNING',
                status_version = status_version + 1,
                attempt_count = attempt_count + 1,
                next_attempt_at = NULL,
                lease_owner = #{workerId},
                lease_token = #{leaseToken},
                lease_expires_at = TIMESTAMPADD(
                    SECOND, #{leaseDurationSeconds}, CURRENT_TIMESTAMP(6)
                ),
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP(6)),
                finished_at = NULL
            WHERE id = #{taskId}
              AND submission_id = #{submissionId}
              AND task_status = #{expectedStatus}
              AND status_version = #{statusVersion}
              AND attempt_count < max_attempts
              AND (
                  #{expectedStatus} = 'QUEUED'
                  OR (#{expectedStatus} IN ('RETRYING', 'WAITING_RETRY')
                      AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP(6)))
                  OR (#{expectedStatus} = 'RUNNING'
                      AND lease_expires_at <= CURRENT_TIMESTAMP(6))
              )
            """)
    int markTaskRunning(
            @Param("taskId") String taskId,
            @Param("submissionId") String submissionId,
            @Param("statusVersion") long statusVersion,
            @Param("expectedStatus") String expectedStatus,
            @Param("workerId") String workerId,
            @Param("leaseToken") String leaseToken,
            @Param("leaseDurationSeconds") long leaseDurationSeconds);

    @Update(
            """
            UPDATE submission
            SET processing_status = 'RUNNING',
                status_version = status_version + 1,
                started_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{submissionId}
              AND processing_status = #{expectedStatus}
              AND status_version = #{statusVersion}
            """)
    int markSubmissionRunning(
            @Param("submissionId") String submissionId,
            @Param("statusVersion") long statusVersion,
            @Param("expectedStatus") String expectedStatus);

    @Update(
            """
            UPDATE judge_task_attempt
            SET attempt_status = 'LEASE_EXPIRED',
                finished_at = CURRENT_TIMESTAMP(6),
                failure_code = 'LEASE_EXPIRED',
                failure_message = 'Worker lease expired before a terminal result was committed'
            WHERE judge_task_id = #{taskId}
              AND lease_token = #{leaseToken}
              AND attempt_status = 'RUNNING'
            """)
    int markAttemptLeaseExpired(
            @Param("taskId") String taskId, @Param("leaseToken") String leaseToken);

    @Update(
            """
            UPDATE judge_task_attempt
            SET attempt_status = 'DEAD_LETTERED',
                heartbeat_at = CURRENT_TIMESTAMP(6),
                finished_at = CURRENT_TIMESTAMP(6),
                failure_code = 'ATTEMPT_LIMIT_EXHAUSTED',
                failure_message = 'Worker lease expired at the configured attempt limit'
            WHERE judge_task_id = #{taskId}
              AND lease_token = #{leaseToken}
              AND attempt_status = 'RUNNING'
            """)
    int markExpiredAttemptDeadLettered(
            @Param("taskId") String taskId, @Param("leaseToken") String leaseToken);

    @Insert(
            """
            INSERT INTO judge_task_attempt (
                id, judge_task_id, attempt_no, lease_token, worker_id,
                attempt_status, started_at, heartbeat_at, lease_expires_at
            )
            SELECT #{attemptId}, id, attempt_count, lease_token, lease_owner,
                   'RUNNING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), lease_expires_at
            FROM judge_task
            WHERE id = #{taskId}
              AND submission_id = #{submissionId}
              AND task_status = 'RUNNING'
              AND lease_token = #{leaseToken}
            """)
    int insertAttempt(
            @Param("attemptId") String attemptId,
            @Param("taskId") String taskId,
            @Param("submissionId") String submissionId,
            @Param("leaseToken") String leaseToken);

    @Update(
            """
            UPDATE judge_task
            SET task_status = #{retryStatus},
                status_version = status_version + 1,
                next_attempt_at = TIMESTAMPADD(
                    SECOND, #{delaySeconds}, CURRENT_TIMESTAMP(6)
                ),
                lease_owner = NULL,
                lease_token = NULL,
                lease_expires_at = NULL,
                last_failure_code = #{failureCode},
                last_failure_message = #{failureMessage},
                finished_at = NULL
            WHERE id = #{taskId}
              AND submission_id = #{submissionId}
              AND task_status = 'RUNNING'
              AND status_version = #{statusVersion}
              AND lease_token = #{leaseToken}
              AND attempt_count < max_attempts
            """)
    int markTaskRetrying(
            @Param("taskId") String taskId,
            @Param("submissionId") String submissionId,
            @Param("statusVersion") long statusVersion,
            @Param("leaseToken") String leaseToken,
            @Param("delaySeconds") long delaySeconds,
            @Param("retryStatus") String retryStatus,
            @Param("failureCode") String failureCode,
            @Param("failureMessage") String failureMessage);

    @Update(
            """
            UPDATE submission
            SET processing_status = #{retryStatus},
                verdict = NULL,
                diagnostic_message = NULL,
                status_version = status_version + 1,
                finished_at = NULL
            WHERE id = #{submissionId}
              AND processing_status = 'RUNNING'
              AND status_version = #{statusVersion}
            """)
    int markSubmissionRetrying(
            @Param("submissionId") String submissionId,
            @Param("statusVersion") long statusVersion,
            @Param("retryStatus") String retryStatus);

    @Insert(
            """
            INSERT INTO outbox_event (
                id, aggregate_type, aggregate_id, event_type, contract_version,
                sequence_no, payload, next_attempt_at
            ) VALUES (
                #{eventId}, 'JUDGE_TASK', #{taskId}, #{eventType}, 1,
                #{sequenceNo},
                JSON_OBJECT(
                    'taskId', #{taskId},
                    'submissionId', #{submissionId},
                    'taskType', 'JUDGE_SUBMISSION',
                    'contractVersion', 1
                ),
                TIMESTAMPADD(SECOND, #{delaySeconds}, CURRENT_TIMESTAMP(6))
            )
            """)
    int insertOutboxEvent(
            @Param("eventId") String eventId,
            @Param("taskId") String taskId,
            @Param("submissionId") String submissionId,
            @Param("eventType") String eventType,
            @Param("sequenceNo") int sequenceNo,
            @Param("delaySeconds") long delaySeconds);

    @Update(
            """
            UPDATE judge_task
            SET task_status = 'DEAD_LETTER',
                status_version = status_version + 1,
                next_attempt_at = NULL,
                lease_owner = NULL,
                lease_token = NULL,
                lease_expires_at = NULL,
                last_failure_code = 'ATTEMPT_LIMIT_EXHAUSTED',
                last_failure_message = 'Judge task exhausted its configured attempts',
                finished_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{taskId}
              AND submission_id = #{submissionId}
              AND task_status = #{expectedStatus}
              AND status_version = #{statusVersion}
              AND attempt_count >= max_attempts
            """)
    int markTaskExhausted(
            @Param("taskId") String taskId,
            @Param("submissionId") String submissionId,
            @Param("expectedStatus") String expectedStatus,
            @Param("statusVersion") long statusVersion);

    @Update(
            """
            UPDATE submission
            SET processing_status = 'SYSTEM_ERROR',
                verdict = NULL,
                diagnostic_message = 'Judging infrastructure failed',
                status_version = status_version + 1,
                finished_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{submissionId}
              AND processing_status = #{expectedStatus}
              AND status_version = #{statusVersion}
            """)
    int markSubmissionSystemError(
            @Param("submissionId") String submissionId,
            @Param("expectedStatus") String expectedStatus,
            @Param("statusVersion") long statusVersion);

    @Update(
            """
            UPDATE judge_task
            SET task_status = #{terminalStatus},
                status_version = status_version + 1,
                next_attempt_at = NULL,
                lease_owner = NULL,
                lease_token = NULL,
                lease_expires_at = NULL,
                finished_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{taskId}
              AND submission_id = #{submissionId}
              AND task_status = 'RUNNING'
              AND status_version = #{statusVersion}
              AND lease_token = #{leaseToken}
            """)
    int markTaskTerminal(
            @Param("taskId") String taskId,
            @Param("submissionId") String submissionId,
            @Param("statusVersion") long statusVersion,
            @Param("leaseToken") String leaseToken,
            @Param("terminalStatus") String terminalStatus);

    @Update(
            """
            UPDATE judge_task
            SET task_status = 'DEAD_LETTER',
                status_version = status_version + 1,
                next_attempt_at = NULL,
                lease_owner = NULL,
                lease_token = NULL,
                lease_expires_at = NULL,
                last_failure_code = #{failureCode},
                last_failure_message = #{failureMessage},
                finished_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{taskId}
              AND submission_id = #{submissionId}
              AND task_status = 'RUNNING'
              AND status_version = #{statusVersion}
              AND lease_token = #{leaseToken}
            """)
    int markTaskDeadLetter(
            @Param("taskId") String taskId,
            @Param("submissionId") String submissionId,
            @Param("statusVersion") long statusVersion,
            @Param("leaseToken") String leaseToken,
            @Param("failureCode") String failureCode,
            @Param("failureMessage") String failureMessage);

    @Update(
            """
            UPDATE submission
            SET processing_status = #{terminalStatus},
                verdict = #{verdict},
                diagnostic_message = #{diagnosticMessage},
                status_version = status_version + 1,
                finished_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{submissionId}
              AND processing_status = 'RUNNING'
              AND status_version = #{statusVersion}
            """)
    int markSubmissionTerminal(
            @Param("submissionId") String submissionId,
            @Param("statusVersion") long statusVersion,
            @Param("terminalStatus") String terminalStatus,
            @Param("verdict") String verdict,
            @Param("diagnosticMessage") String diagnosticMessage);

    @Update(
            """
            UPDATE judge_task_attempt
            SET attempt_status = #{attemptStatus},
                heartbeat_at = CURRENT_TIMESTAMP(6),
                finished_at = CURRENT_TIMESTAMP(6),
                failure_code = #{failureCode},
                failure_message = #{failureMessage}
            WHERE id = #{attemptId}
              AND judge_task_id = #{taskId}
              AND lease_token = #{leaseToken}
              AND attempt_status = 'RUNNING'
            """)
    int markAttemptTerminal(
            @Param("attemptId") String attemptId,
            @Param("taskId") String taskId,
            @Param("leaseToken") String leaseToken,
            @Param("attemptStatus") String attemptStatus,
            @Param("failureCode") String failureCode,
            @Param("failureMessage") String failureMessage);

    @Update(
            """
            UPDATE judge_task
            SET lease_expires_at = TIMESTAMPADD(
                    SECOND, #{leaseDurationSeconds}, CURRENT_TIMESTAMP(6)
                )
            WHERE id = #{taskId}
              AND submission_id = #{submissionId}
              AND task_status = 'RUNNING'
              AND lease_token = #{leaseToken}
              AND lease_expires_at > CURRENT_TIMESTAMP(6)
            """)
    int renewTaskLease(
            @Param("taskId") String taskId,
            @Param("submissionId") String submissionId,
            @Param("leaseToken") String leaseToken,
            @Param("leaseDurationSeconds") long leaseDurationSeconds);

    @Update(
            """
            UPDATE judge_task_attempt a
            JOIN judge_task jt ON jt.id = a.judge_task_id
            SET a.heartbeat_at = CURRENT_TIMESTAMP(6),
                a.lease_expires_at = jt.lease_expires_at
            WHERE a.id = #{attemptId}
              AND a.judge_task_id = #{taskId}
              AND a.attempt_status = 'RUNNING'
              AND a.lease_token = #{leaseToken}
              AND jt.task_status = 'RUNNING'
              AND jt.lease_token = #{leaseToken}
            """)
    int renewAttemptLease(
            @Param("attemptId") String attemptId,
            @Param("taskId") String taskId,
            @Param("leaseToken") String leaseToken);
}
