package com.forgeoj.worker.sandbox;

import com.forgeoj.worker.snapshot.JudgeTaskSnapshot;

public interface SandboxRuntime {

    void verifyAvailable();

    void cleanupManagedContainers();

    SandboxContainer prepare(JudgeTaskSnapshot snapshot, String attemptId);

    SandboxExecutionResult execute(JudgeTaskSnapshot snapshot, String attemptId);

    default SandboxOutputPreview generate(JudgeTaskSnapshot snapshot,String attemptId) {throw new SandboxException("Output generation unsupported");}

    void cleanup(SandboxContainer container);
}
