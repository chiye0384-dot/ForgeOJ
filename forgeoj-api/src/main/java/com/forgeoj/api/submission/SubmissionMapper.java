package com.forgeoj.api.submission;

import java.util.Optional;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface SubmissionMapper {

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
              AND p.status = 'ACTIVE'
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
