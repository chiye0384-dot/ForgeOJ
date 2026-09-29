package com.forgeoj.worker.sandbox;

import com.forgeoj.worker.snapshot.JudgeTaskSnapshot;

public interface SandboxRuntime {

    void verifyAvailable();

    void cleanupManagedContainers();

    SandboxContainer prepare(JudgeTaskSnapshot snapshot);

    void cleanup(SandboxContainer container);
}
