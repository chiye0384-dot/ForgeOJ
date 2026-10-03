package com.forgeoj.worker.sandbox;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Only committed, permanently closed attempts authorize orphan removal. */
@Mapper
public interface SandboxAttemptLookup {

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM judge_task_attempt
                WHERE id = #{attemptId} AND judge_task_id = #{taskId}
                  AND attempt_status IN ('SUCCEEDED', 'RETRYABLE_FAILURE', 'LEASE_EXPIRED', 'DEAD_LETTERED')
                  AND finished_at IS NOT NULL
            ) OR EXISTS (
                SELECT 1 FROM content_validation_attempt WHERE id=#{attemptId} AND job_id=#{taskId}
                  AND attempt_status IN ('SUCCEEDED','RETRYABLE_FAILURE','LEASE_EXPIRED','DEAD_LETTERED')
                  AND finished_at IS NOT NULL
            )
            """)
    boolean isClosed(@Param("taskId") String taskId, @Param("attemptId") String attemptId);
}
