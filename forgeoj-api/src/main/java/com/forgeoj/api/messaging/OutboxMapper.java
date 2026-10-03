package com.forgeoj.api.messaging;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface OutboxMapper {

    @Select(
            """
            SELECT o.id,
                   o.event_type AS eventType,
                   o.sequence_no AS sequenceNo,
                   CAST(o.payload AS CHAR) AS payload,
                   o.publish_attempts AS publishAttempts,
                   o.aggregate_id AS judgeTaskId,
                   jt.submission_id AS submissionId
            FROM outbox_event o
            LEFT JOIN judge_task jt ON jt.id = o.aggregate_id
            WHERE o.published_at IS NULL
              AND o.failed_at IS NULL
              AND o.next_attempt_at <= CURRENT_TIMESTAMP(6)
              AND ((o.aggregate_type = 'JUDGE_TASK'
                AND o.event_type IN ('JUDGE_TASK_QUEUED', 'JUDGE_TASK_DEAD_LETTERED'))
                OR (o.aggregate_type='CONTENT_VALIDATION'
                AND o.event_type IN ('CONTENT_VALIDATION_QUEUED','CONTENT_VALIDATION_DEAD_LETTERED')))
              AND o.contract_version = 1
            ORDER BY o.next_attempt_at, o.created_at, o.id
            LIMIT #{limit}
            """)
    List<OutboxEventRow> findPending(@Param("limit") int limit);

    @Update(
            """
            UPDATE outbox_event
            SET publish_attempts = publish_attempts + 1,
                last_attempt_at = CURRENT_TIMESTAMP(6),
                last_error_code = NULL,
                published_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{eventId}
              AND published_at IS NULL
              AND failed_at IS NULL
              AND publish_attempts = #{expectedAttempts}
            """)
    int markPublished(
            @Param("eventId") String eventId,
            @Param("expectedAttempts") int expectedAttempts);

    @Update(
            """
            UPDATE outbox_event
            SET failed_at = CASE
                    WHEN publish_attempts + 1 >= #{maximumAttempts}
                        THEN CURRENT_TIMESTAMP(6)
                    ELSE NULL
                END,
                publish_attempts = publish_attempts + 1,
                last_attempt_at = CURRENT_TIMESTAMP(6),
                last_error_code = #{errorCode},
                next_attempt_at = TIMESTAMPADD(
                    SECOND, #{delaySeconds}, CURRENT_TIMESTAMP(6)
                )
            WHERE id = #{eventId}
              AND published_at IS NULL
              AND failed_at IS NULL
              AND publish_attempts = #{expectedAttempts}
            """)
    int recordPublishFailure(
            @Param("eventId") String eventId,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("maximumAttempts") int maximumAttempts,
            @Param("delaySeconds") long delaySeconds,
            @Param("errorCode") String errorCode);
}
