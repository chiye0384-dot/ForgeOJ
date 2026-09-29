package com.forgeoj.worker.task;

import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface JudgeTaskMapper {

    @Select(
            """
            SELECT jt.id AS taskId,
                   jt.submission_id AS submissionId,
                   jt.task_type AS taskType,
                   jt.contract_version AS contractVersion,
                   jt.task_status AS taskStatus,
                   jt.status_version AS taskStatusVersion,
                   s.processing_status AS submissionStatus,
                   s.status_version AS submissionStatusVersion
            FROM judge_task jt
            JOIN submission s ON s.id = jt.submission_id
            WHERE jt.id = #{taskId}
            FOR UPDATE
            """)
    Optional<JudgeTaskClaimRow> findForUpdate(@Param("taskId") String taskId);

    @Update(
            """
            UPDATE judge_task
            SET task_status = 'RUNNING',
                status_version = status_version + 1,
                started_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{taskId}
              AND submission_id = #{submissionId}
              AND task_status = 'QUEUED'
              AND status_version = #{statusVersion}
            """)
    int markTaskRunning(
            @Param("taskId") String taskId,
            @Param("submissionId") String submissionId,
            @Param("statusVersion") long statusVersion);

    @Update(
            """
            UPDATE submission
            SET processing_status = 'RUNNING',
                status_version = status_version + 1,
                started_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{submissionId}
              AND processing_status = 'QUEUED'
              AND status_version = #{statusVersion}
            """)
    int markSubmissionRunning(
            @Param("submissionId") String submissionId,
            @Param("statusVersion") long statusVersion);
}
