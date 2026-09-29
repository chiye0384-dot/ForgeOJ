package com.forgeoj.worker.snapshot;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface JudgeTaskSnapshotMapper {

    @Select(
            """
            SELECT jt.id AS taskId,
                   s.id AS submissionId,
                   s.judge_version_id AS judgeVersionId,
                   s.language,
                   s.source_code AS sourceCode,
                   s.source_sha256 AS sourceSha256,
                   s.time_limit_ms AS timeLimitMs,
                   s.memory_limit_mb AS memoryLimitMb,
                   s.output_limit_bytes AS outputLimitBytes,
                   s.comparison_rule_version AS comparisonRuleVersion,
                   s.sandbox_policy_version AS sandboxPolicyVersion,
                   s.java_image_digest AS javaImageDigest,
                   s.test_dataset_sha256 AS testDatasetSha256,
                   jv.test_dataset_sha256 AS storedTestDatasetSha256
            FROM judge_task jt
            JOIN submission s ON s.id = jt.submission_id
            JOIN problem_judge_version jv ON jv.id = s.judge_version_id
            WHERE jt.id = #{taskId}
              AND jt.submission_id = #{submissionId}
              AND jt.task_type = #{taskType}
              AND jt.contract_version = #{contractVersion}
              AND jt.task_status = 'RUNNING'
              AND s.processing_status = 'RUNNING'
            """)
    Optional<JudgeTaskSnapshotRow> findRunningSnapshot(
            @Param("taskId") String taskId,
            @Param("submissionId") String submissionId,
            @Param("taskType") String taskType,
            @Param("contractVersion") int contractVersion);

    @Select(
            """
            SELECT ordinal,
                   input_data_gzip AS inputDataGzip,
                   expected_output_gzip AS expectedOutputGzip,
                   input_size_bytes AS inputSizeBytes,
                   output_size_bytes AS outputSizeBytes,
                   input_sha256 AS inputSha256,
                   output_sha256 AS outputSha256
            FROM problem_test_case
            WHERE judge_version_id = #{judgeVersionId}
            ORDER BY ordinal
            """)
    List<HiddenTestCaseRow> findHiddenTestCases(@Param("judgeVersionId") long judgeVersionId);
}
