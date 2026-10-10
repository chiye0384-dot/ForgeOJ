/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.cache;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface CacheMapper {
    record Event(long id,String namespace,long oldRevision,int attempts) {}
    @Select("SELECT revision FROM cache_epoch WHERE namespace='public'") long publicRevision();
    @Select("SELECT revision FROM cache_epoch WHERE namespace='public' FOR UPDATE") long lockPublic();
    @Update("UPDATE cache_epoch SET revision=revision+1 WHERE namespace='public'") void advance();
    @Insert("INSERT INTO cache_invalidation_outbox(namespace,old_revision) VALUES('public',#{revision})") void append(long revision);
    @Select("SELECT id FROM cache_invalidation_outbox WHERE delivered_at IS NULL AND next_attempt_at<=UTC_TIMESTAMP(6) ORDER BY id LIMIT 20") List<Long> due();
    @Select("SELECT id,namespace,old_revision,attempts FROM cache_invalidation_outbox WHERE id=#{id} AND delivered_at IS NULL AND next_attempt_at<=UTC_TIMESTAMP(6) FOR UPDATE SKIP LOCKED") Optional<Event> lock(long id);
    @Update("UPDATE cache_invalidation_outbox SET delivered_at=UTC_TIMESTAMP(6),error_code=NULL WHERE id=#{id}") void delivered(long id);
    @Update("UPDATE cache_invalidation_outbox SET attempts=LEAST(attempts+1,1000000),next_attempt_at=TIMESTAMPADD(SECOND,#{delay},UTC_TIMESTAMP(6)),error_code=#{error} WHERE id=#{id}")
    void later(@Param("id")long id,@Param("delay")int delay,@Param("error")String error);
}
