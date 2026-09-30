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
            SELECT id, event_type AS eventType, CAST(payload AS CHAR) AS payload
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
            SET published_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{eventId}
              AND published_at IS NULL
            """)
    int markPublished(@Param("eventId") String eventId);
}
