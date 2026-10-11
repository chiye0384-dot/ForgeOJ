/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.observability;
import java.util.List;
import org.apache.ibatis.annotations.*;
@Mapper
public interface MonitoringMapper {
    record Group(String kind,String state,long value) {}
    record Times(String kind,double oldest,double seconds,long completed,long retries) {}
    record Backlog(long outboxPending,long outboxFailed,long invalidationPending,long invalidationFailed,long searchPending,long searchDead,long epochGap) {}
    String TASKS="""
        (SELECT 'FORMAL' kind,task_status state,created_at,started_at,finished_at,attempt_count FROM judge_task
         UNION ALL SELECT execution_kind,processing_status,created_at,started_at,finished_at,attempt_count FROM content_validation_job
         UNION ALL SELECT 'SELF_TEST',processing_status,created_at,started_at,finished_at,attempt_count FROM self_test_job) t
        """;
    @Select("SELECT kind,state,COUNT(*) value FROM "+TASKS+" GROUP BY kind,state") @Options(timeout=2)
    List<Group> tasks();
    @Select("SELECT 'FORMAL' kind,verdict state,COUNT(*) value FROM submission WHERE verdict IS NOT NULL GROUP BY verdict") @Options(timeout=2)
    List<Group> verdicts();
    @Select("SELECT kind,COALESCE(MAX(CASE WHEN state IN ('QUEUED','RETRYING','WAITING_RETRY') THEN GREATEST(0,TIMESTAMPDIFF(MICROSECOND,created_at,UTC_TIMESTAMP(6))/1000000.0) END),0) oldest,COALESCE(SUM(CASE WHEN state='FINISHED' THEN GREATEST(0,TIMESTAMPDIFF(MICROSECOND,started_at,finished_at)/1000000.0) END),0) seconds,SUM(state='FINISHED') completed,COALESCE(SUM(CASE WHEN attempt_count>1 THEN attempt_count-1 ELSE 0 END),0) retries FROM "+TASKS+" GROUP BY kind") @Options(timeout=2)
    List<Times> times();
    @Select("""
        SELECT (SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL) outboxPending,
          (SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL AND failed_at IS NOT NULL) outboxFailed,
          (SELECT COUNT(*) FROM cache_invalidation_outbox WHERE delivered_at IS NULL) invalidationPending,
          (SELECT COUNT(*) FROM cache_invalidation_outbox WHERE delivered_at IS NULL AND attempts>0) invalidationFailed,
          (SELECT COUNT(*) FROM public_search_outbox WHERE published_at IS NULL) searchPending,
          (SELECT COUNT(*) FROM public_search_delivery WHERE status='DEAD_LETTER') searchDead,
          CASE WHEN e.revision>c.readable_epoch THEN e.revision-c.readable_epoch ELSE 0 END epochGap FROM cache_epoch e JOIN public_search_control c ON c.id=1 WHERE e.namespace='public'
        """) @Options(timeout=2)
    Backlog backlog();
    @Select("SELECT 'SEARCH' kind,status state,COUNT(*) value FROM public_search_rebuild GROUP BY status") @Options(timeout=2)
    List<Group> rebuilds();
}
