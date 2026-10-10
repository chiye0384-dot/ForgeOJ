/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.cache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class CacheInvalidationProcessor {
    private final CacheMapper mapper;private final RedisSupport redis;
    public CacheInvalidationProcessor(CacheMapper mapper,RedisSupport redis){this.mapper=mapper;this.redis=redis;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void process(long id) {
        var event=mapper.lock(id);if(event.isEmpty()) return;
        var e=event.get();Long remaining=redis.invalidate(PublicCache.index(e.oldRevision()),e.oldRevision());
        if(remaining!=null && remaining==0) mapper.delivered(id);
        else mapper.later(id,remaining==null?Math.min(60,1<<Math.min(e.attempts(),6)):1,remaining==null?"REDIS_UNAVAILABLE":"BATCH_REMAINING");
    }
}
