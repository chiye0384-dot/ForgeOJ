/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.selftest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SelfTestCleanup {
    private final SelfTestMapper mapper;private final TransactionTemplate tx;private final boolean enabled;
    public SelfTestCleanup(SelfTestMapper mapper,TransactionTemplate tx,@Value("${forgeoj.self-test.cleanup.enabled:true}") boolean enabled){this.mapper=mapper;this.tx=tx;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${forgeoj.self-test.cleanup.fixed-delay-ms:60000}")
    public void scheduled(){if(enabled) purge();}
    public int purge(){int count=0;for(String id:mapper.expired()) {tx.executeWithoutResult(ignored->{mapper.purgeOutput(id);mapper.purgePayload(id);});count++;}return count;}
}
