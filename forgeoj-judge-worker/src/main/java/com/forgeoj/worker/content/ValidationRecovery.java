/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.content;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="forgeoj.worker.recovery.enabled",havingValue="true")
public class ValidationRecovery {
    private final ValidationMapper mapper;
    private final ValidationLeaseService leases;
    public ValidationRecovery(ValidationMapper mapper,ValidationLeaseService leases) {this.mapper=mapper;this.leases=leases;}
    @Scheduled(fixedDelayString="${forgeoj.worker.recovery.fixed-delay-ms:5000}")
    public void recover() {
        for(String id:mapper.expired()) try{leases.recover(id);}catch(RuntimeException failure){LoggerFactory.getLogger(ValidationRecovery.class).atWarn().addKeyValue("event","content.recovery_failed").addKeyValue("contentJobId",id).log("Content validation recovery unavailable");break;}
    }
}
