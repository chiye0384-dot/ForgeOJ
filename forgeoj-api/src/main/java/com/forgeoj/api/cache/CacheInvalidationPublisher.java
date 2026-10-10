/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.cache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="forgeoj.redis.enabled",havingValue="true")
public class CacheInvalidationPublisher {
    private final CacheMapper mapper;private final CacheInvalidationProcessor processor;
    public CacheInvalidationPublisher(CacheMapper mapper,CacheInvalidationProcessor processor){this.mapper=mapper;this.processor=processor;}
    @Scheduled(fixedDelayString="${forgeoj.redis.invalidation-delay-ms:1000}")
    public void publish(){for(long id:mapper.due())try{processor.process(id);}catch(org.springframework.dao.DataAccessException unavailable){return;}}
}
