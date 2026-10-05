/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.selftest;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="forgeoj.worker.recovery.enabled",havingValue="true")
public class SelfTestRecovery {
    private final SelfTestMapper mapper;
    private final SelfTestLeaseService leases;
    public SelfTestRecovery(SelfTestMapper mapper,SelfTestLeaseService leases) {this.mapper=mapper;this.leases=leases;}
    @Scheduled(fixedDelayString="${forgeoj.worker.recovery.fixed-delay-ms:5000}")
    public void recover() {
        for(String id:mapper.expired()) try{leases.recover(id);}catch(RuntimeException failure){LoggerFactory.getLogger(SelfTestRecovery.class).atWarn().addKeyValue("event","selftest.recovery_failed").addKeyValue("selfTestRunId",id).log("Content self-test recovery unavailable");break;}
    }
}
