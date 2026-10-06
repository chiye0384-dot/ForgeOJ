package com.forgeoj.api.submission;

import java.util.Optional;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface SubmissionMapper {
    @Select("SELECT p.id AS problemId,jv.id AS judgeVersionId,jv.time_limit_ms AS timeLimitMs,jv.memory_limit_mb AS memoryLimitMb,jv.output_limit_bytes AS outputLimitBytes,jv.comparison_rule_version AS comparisonRuleVersion,jv.sandbox_policy_version AS sandboxPolicyVersion,jv.java_image_digest AS javaImageDigest,jv.test_dataset_sha256 AS testDatasetSha256 FROM problem p JOIN problem_judge_version jv ON jv.id=p.current_judge_version_id JOIN classroom_problem c ON c.problem_id=p.id AND c.classroom_id=p.classroom_id WHERE p.classroom_id=#{room} AND p.scope='CLASSROOM' AND p.slug=#{slug} AND p.status='ACTIVE' FOR SHARE")
    Optional<JudgeVersionSnapshot> classroomVersion(@Param("room") String room,@Param("slug") String slug);
    @Select("SELECT EXISTS(SELECT 1 FROM submission WHERE user_id=#{user} AND client_request_id=#{request} AND problem_id=#{problem} AND source_sha256=#{hash} AND language='JAVA_21')")
    boolean matches(@Param("user") long user,@Param("request") String request,@Param("problem") long problem,@Param("hash") String hash);

    @Select("""
            SELECT s.id AS submissionId, s.processing_status AS processingStatus,
                   s.status_version AS statusVersion
            FROM submission s JOIN user_account u ON u.id = s.user_id
            WHERE s.id = #{submissionId} AND s.user_id = #{userId} AND u.status = 'ACTIVE'
            LIMIT 1
            """)
    Optional<SubmissionResult> findNoticeByOwner(
            @Param("userId") long userId, @Param("submissionId") String submissionId);

    @Select(
            """
            SELECT jt.id FROM judge_task jt
            JOIN submission s ON s.id = jt.submission_id
            WHERE s.id = #{submissionId} AND s.user_id = #{userId}
            """)
    Optional<String> findTaskIdByOwner(
            @Param("userId") long userId, @Param("submissionId") String submissionId);

    @Select(
            """
            SELECT jt.id AS taskId, s.id AS submissionId,
                   jt.task_status AS taskStatus, s.processing_status AS processingStatus,
                   jt.status_version AS taskVersion, s.status_version AS submissionVersion
            FROM judge_task jt
            JOIN submission s ON s.id = jt.submission_id
            WHERE jt.id = #{taskId} AND s.user_id = #{userId}
            FOR UPDATE
            """)
    Optional<SubmissionCancellationRow> findCancellationForOwnerForUpdate(
            @Param("userId") long userId, @Param("taskId") String taskId);

    @Update(
            """
            UPDATE judge_task
            SET task_status = 'CANCELLED', status_version = status_version + 1,
                finished_at = CURRENT_TIMESTAMP(6), next_attempt_at = NULL
            WHERE id = #{taskId} AND task_status = 'QUEUED' AND status_version = #{version}
            """)
    int cancelQueuedTask(@Param("taskId") String taskId, @Param("version") long version);

    @Update(
            """
            UPDATE submission
            SET processing_status = 'CANCELLED', status_version = status_version + 1,
                finished_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{submissionId} AND processing_status = 'QUEUED'
              AND status_version = #{version}
            """)
    int cancelQueuedSubmission(
            @Param("submissionId") String submissionId, @Param("version") long version);

    @Select(
            """
            SELECT EXISTS(
                SELECT 1
                FROM user_account
                WHERE id = #{userId}
                  AND status = 'ACTIVE'
            )
            """)
    boolean isActiveUser(@Param("userId") long userId);

    @Select(
            """
            SELECT user_id
            FROM user_judge_quota_lock
            WHERE user_id = #{userId}
            FOR UPDATE
            """)
    Optional<Long> lockQuota(@Param("userId") long userId);

    @Select(
            """
            SELECT (SELECT COUNT(*) FROM submission s
            WHERE s.user_id = #{userId} AND s.processing_status IN ('QUEUED', 'RETRYING'))
            + (SELECT COUNT(*) FROM content_validation_job
               WHERE owner_id = #{userId} AND processing_status = 'QUEUED')
            + (SELECT COUNT(*) FROM self_test_job WHERE owner_id=#{userId} AND processing_status='QUEUED')
            """)
    int countQueuedOrRetrying(@Param("userId") long userId);

    @Select(
            """
            SELECT id AS submissionId,
                   processing_status AS processingStatus,
                   status_version AS statusVersion
            FROM submission
            WHERE user_id = #{userId}
              AND client_request_id = #{clientRequestId}
            LIMIT 1
            """)
    Optional<SubmissionResult> findResultByRequest(
            @Param("userId") long userId,
            @Param("clientRequestId") String clientRequestId);

    @Select(
            """
            SELECT id AS submissionId,
                   processing_status AS processingStatus,
                   status_version AS statusVersion,
                   verdict,
                   diagnostic_message AS diagnosticMessage
            FROM submission
            WHERE id = #{submissionId}
              AND user_id = #{userId}
            LIMIT 1
            """)
    Optional<SubmissionStatus> findStatusByOwner(
            @Param("userId") long userId,
            @Param("submissionId") String submissionId);

    @Select(
            """
            SELECT p.id AS problemId,
                   jv.id AS judgeVersionId,
                   jv.time_limit_ms AS timeLimitMs,
                   jv.memory_limit_mb AS memoryLimitMb,
                   jv.output_limit_bytes AS outputLimitBytes,
                   jv.comparison_rule_version AS comparisonRuleVersion,
                   jv.sandbox_policy_version AS sandboxPolicyVersion,
                   jv.java_image_digest AS javaImageDigest,
                   jv.test_dataset_sha256 AS testDatasetSha256
            FROM problem p
            JOIN problem_judge_version jv ON jv.id = p.current_judge_version_id
            WHERE p.slug = #{slug}
              AND p.status = 'ACTIVE' AND p.scope = 'PUBLIC'
            LIMIT 1
            """)
    Optional<JudgeVersionSnapshot> findActiveJudgeVersion(@Param("slug") String slug);

    @Insert(
            """
            INSERT INTO submission (
                id, user_id, problem_id, judge_version_id, client_request_id,
                language, source_code, source_sha256,
                time_limit_ms, memory_limit_mb, output_limit_bytes,
                comparison_rule_version, sandbox_policy_version,
                java_image_digest, test_dataset_sha256,
                processing_status, verdict, status_version
            ) VALUES (
                #{id}, #{userId}, #{problemId}, #{judgeVersionId}, #{clientRequestId},
                #{language}, #{sourceCode}, #{sourceSha256},
                #{timeLimitMs}, #{memoryLimitMb}, #{outputLimitBytes},
                #{comparisonRuleVersion}, #{sandboxPolicyVersion},
                #{javaImageDigest}, #{testDatasetSha256},
                'QUEUED', NULL, 0
            )
            """)
    int insertSubmission(NewSubmissionRow submission);

    @Insert(
            """
            INSERT INTO judge_task (
                id, submission_id, task_type, contract_version, task_status, status_version
            ) VALUES (
                #{taskId}, #{submissionId}, 'JUDGE_SUBMISSION', 1, 'QUEUED', 0
            )
            """)
    int insertJudgeTask(
            @Param("taskId") String taskId, @Param("submissionId") String submissionId);

    @Insert(
            """
            INSERT INTO outbox_event (
                id, aggregate_type, aggregate_id, event_type, contract_version, payload
            ) VALUES (
                #{eventId}, 'JUDGE_TASK', #{taskId}, 'JUDGE_TASK_QUEUED', 1,
                CAST(#{payload} AS JSON)
            )
            """)
    int insertOutboxEvent(
            @Param("eventId") String eventId,
            @Param("taskId") String taskId,
            @Param("payload") String payload);
}
