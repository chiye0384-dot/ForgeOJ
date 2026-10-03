/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.content;

import com.forgeoj.worker.sandbox.*;
import com.forgeoj.worker.snapshot.JudgeTaskSnapshotException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name={"forgeoj.worker.consumer.enabled","forgeoj.worker.sandbox.enabled"},havingValue="true")
public class ValidationRunner {
    private final ValidationSnapshotLoader snapshots;
    private final SandboxRuntime runtime;
    private final ValidationLeaseService leases;
    private final ValidationHeartbeat heartbeat;
    public ValidationRunner(ValidationSnapshotLoader snapshots,SandboxRuntime runtime,ValidationLeaseService leases,ValidationHeartbeat heartbeat) {this.snapshots=snapshots;this.runtime=runtime;this.leases=leases;this.heartbeat=heartbeat;}
    public void run(ValidationLeaseService.Claim claim) {
        SandboxExecutionResult reference,solution;
        try(var ignored=heartbeat.start(claim)) {
            var programs=snapshots.load(claim);
            reference=runtime.execute(programs.reference(),claim.attemptId());
            leases.renew(claim); // Check durable ownership before executing the second independent object.
            solution=runtime.execute(programs.solution(),claim.attemptId());
        } catch(JudgeTaskSnapshotException | InvalidSandboxConfigurationException invalid) {leases.failure(claim,true);return;}
        catch(RuntimeException failure) {leases.failure(claim,false);return;}
        leases.finish(claim,reference.outcome().name(),solution.outcome().name());
    }
}
