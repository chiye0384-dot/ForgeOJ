package com.forgeoj.worker.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"forgeoj.worker.sandbox.enabled", "forgeoj.worker.recovery.enabled"}, havingValue = "true")
final class SandboxRecoveryCleaner {

    private static final Logger LOG = LoggerFactory.getLogger(SandboxRecoveryCleaner.class);
    private final SandboxRuntime runtime;

    SandboxRecoveryCleaner(SandboxRuntime runtime) { this.runtime = runtime; }

    @Scheduled(initialDelayString = "${forgeoj.worker.sandbox.cleanup-delay-ms:5000}",
            fixedDelayString = "${forgeoj.worker.sandbox.cleanup-delay-ms:5000}")
    void cleanClosedAttempts() {
        try {
            runtime.cleanupManagedContainers();
        } catch (RuntimeException unavailable) {
            LOG.atWarn().addKeyValue("event", "sandbox.cleanup_failed")
                    .addKeyValue("failureCode", "SANDBOX_CLEANUP_FAILURE").log("Sandbox recovery cleanup failed");
        }
    }
}
