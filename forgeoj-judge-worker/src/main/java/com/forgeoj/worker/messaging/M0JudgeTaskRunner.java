package com.forgeoj.worker.messaging;

import com.forgeoj.worker.sandbox.SandboxExecutionResult;
import com.forgeoj.worker.sandbox.SandboxRuntime;
import com.forgeoj.worker.snapshot.JudgeTaskSnapshot;
import com.forgeoj.worker.snapshot.JudgeTaskSnapshotLoader;
import com.forgeoj.worker.task.JudgeTaskCompletionService;
import com.forgeoj.worker.task.ClaimedJudgeTask;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"forgeoj.worker.consumer.enabled", "forgeoj.worker.sandbox.enabled"},
        havingValue = "true")
final class M0JudgeTaskRunner implements JudgeTaskRunner {

    private final JudgeTaskSnapshotLoader snapshotLoader;
    private final SandboxRuntime sandboxRuntime;
    private final JudgeTaskCompletionService completionService;

    M0JudgeTaskRunner(
            JudgeTaskSnapshotLoader snapshotLoader,
            SandboxRuntime sandboxRuntime,
            JudgeTaskCompletionService completionService) {
        this.snapshotLoader = snapshotLoader;
        this.sandboxRuntime = sandboxRuntime;
        this.completionService = completionService;
    }

    @Override
    public void run(ClaimedJudgeTask claimedTask) {
        JudgeTaskMessage message = claimedTask.message();
        SandboxExecutionResult result;
        try {
            JudgeTaskSnapshot snapshot = snapshotLoader.load(message);
            result = sandboxRuntime.execute(snapshot);
        } catch (RuntimeException platformFailure) {
            try {
                completionService.failSystem(claimedTask);
                return;
            } catch (RuntimeException writeFailure) {
                writeFailure.addSuppressed(platformFailure);
                throw writeFailure;
            }
        }
        completionService.finish(claimedTask, result);
    }
}
