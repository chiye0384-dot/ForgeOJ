package com.forgeoj.worker.task;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface JudgeTaskRecoveryMapper {

    @Select(
            """
            SELECT jt.id AS taskId,
                   jt.submission_id AS submissionId,
                   jt.task_type AS taskType,
                   jt.contract_version AS contractVersion
            FROM judge_task jt
            JOIN submission s ON s.id = jt.submission_id
            WHERE (jt.task_status = s.processing_status
                   OR (jt.task_status = 'WAITING_RETRY' AND s.processing_status = 'RUNNING'))
              AND (
                  (jt.task_status = 'RUNNING'
                      AND jt.lease_expires_at <= CURRENT_TIMESTAMP(6))
                  OR (jt.task_status = 'QUEUED'
                      AND jt.next_attempt_at IS NOT NULL
                      AND jt.next_attempt_at <= CURRENT_TIMESTAMP(6))
                  OR (jt.task_status IN ('RETRYING', 'WAITING_RETRY')
                      AND jt.next_attempt_at <= CURRENT_TIMESTAMP(6))
              )
            ORDER BY CASE jt.task_status
                         WHEN 'RUNNING' THEN jt.lease_expires_at
                         ELSE jt.next_attempt_at
                     END,
                     jt.id
            LIMIT #{limit}
            """)
    List<JudgeTaskRecoveryCandidate> findDue(@Param("limit") int limit);
}
