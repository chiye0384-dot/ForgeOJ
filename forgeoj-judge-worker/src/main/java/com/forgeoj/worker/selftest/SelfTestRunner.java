/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.selftest;

import com.forgeoj.worker.sandbox.*;
import com.forgeoj.worker.snapshot.JudgeTaskSnapshotException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name={"forgeoj.worker.consumer.enabled","forgeoj.worker.sandbox.enabled"},havingValue="true")
public class SelfTestRunner {
    private final SelfTestSnapshotLoader snapshots;private final SandboxRuntime runtime;private final SelfTestLeaseService leases;private final SelfTestHeartbeat heartbeat;
    public SelfTestRunner(SelfTestSnapshotLoader snapshots,SandboxRuntime runtime,SelfTestLeaseService leases,SelfTestHeartbeat heartbeat){this.snapshots=snapshots;this.runtime=runtime;this.leases=leases;this.heartbeat=heartbeat;}
    public void run(SelfTestLeaseService.Claim claim){
        SandboxOutputPreview result;
        try(var ignored=heartbeat.start(claim)){result=runtime.generate(snapshots.load(claim),claim.attemptId());}
        catch(JudgeTaskSnapshotException | InvalidSandboxConfigurationException invalid){leases.failure(claim,true);return;}
        catch(RuntimeException failure){leases.failure(claim,false);return;}
        leases.finish(claim,result);
    }
}
