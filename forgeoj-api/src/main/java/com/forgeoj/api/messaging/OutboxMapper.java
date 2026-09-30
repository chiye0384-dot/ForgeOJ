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
            SELECT id,
                   event_type AS eventType,
                   CAST(payload AS CHAR) AS payload,
                   publish_attempts AS publishAttempts
            FROM outbox_event
            WHERE published_at IS NULL
              AND failed_at IS NULL
              AND next_attempt_at <= CURRENT_TIMESTAMP(6)
              AND aggregate_type = 'JUDGE_TASK'
              AND event_type IN ('JUDGE_TASK_QUEUED', 'JUDGE_TASK_DEAD_LETTERED')
              AND contract_version = 1
            ORDER BY next_attempt_at, created_at, id
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
