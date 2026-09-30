package com.forgeoj.worker.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.forgeoj.worker.sandbox.SandboxExecutionResult;
import com.forgeoj.worker.sandbox.SandboxOutcome;
import com.forgeoj.worker.sandbox.SandboxRuntime;
import com.forgeoj.worker.snapshot.JudgeTaskSnapshot;
import com.forgeoj.worker.snapshot.JudgeTaskSnapshotException;
import com.forgeoj.worker.snapshot.JudgeTaskSnapshotLoader;
import com.forgeoj.worker.snapshot.JudgeTestCase;
import com.forgeoj.worker.task.JudgeTaskCompletionService;
import com.forgeoj.worker.task.ClaimedJudgeTask;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class M0JudgeTaskRunnerTests {

    private static final JudgeTaskMessage MESSAGE =
            new JudgeTaskMessage(
                    "b844c173-9436-4d35-a45c-a6f5041f7d20",
                    "a9987de8-880d-42af-b463-06ae4f7b9717",
                    "JUDGE_SUBMISSION",
                    1);
    private static final ClaimedJudgeTask CLAIM =
            new ClaimedJudgeTask(
                    MESSAGE,
                    "c5862430-ab78-436f-b78c-bcdeb728503a",
                    1,
                    "b806756b-18bf-482b-83eb-9536d59d57aa",
                    "runner-test-worker");

    private JudgeTaskSnapshotLoader loader;
    private SandboxRuntime sandbox;
    private JudgeTaskCompletionService completion;
    private M0JudgeTaskRunner runner;

    @BeforeEach
    void setUp() {
        loader = mock(JudgeTaskSnapshotLoader.class);
        sandbox = mock(SandboxRuntime.class);
        completion = mock(JudgeTaskCompletionService.class);
        runner = new M0JudgeTaskRunner(loader, sandbox, completion);
    }

    @Test
    void loadsExecutesAndCommitsTerminalResultInOrder() {
        JudgeTaskSnapshot snapshot = snapshot();
        SandboxExecutionResult result = SandboxExecutionResult.of(SandboxOutcome.ACCEPTED);
        when(loader.load(MESSAGE)).thenReturn(snapshot);
        when(sandbox.execute(snapshot)).thenReturn(result);

        runner.run(CLAIM);

        var order = inOrder(loader, sandbox, completion);
        order.verify(loader).load(MESSAGE);
        order.verify(sandbox).execute(snapshot);
        order.verify(completion).finish(CLAIM, result);
    }

    @Test
    void recordsSystemErrorWhenSnapshotOrSandboxFails() {
        when(loader.load(MESSAGE)).thenThrow(new JudgeTaskSnapshotException("tampered"));

        runner.run(CLAIM);

        verify(completion).failSystem(CLAIM);
        verifyNoInteractions(sandbox);
    }

    @Test
    void propagatesTerminalWriteFailureSoListenerCannotAck() {
        JudgeTaskSnapshot snapshot = snapshot();
        SandboxExecutionResult result = SandboxExecutionResult.of(SandboxOutcome.ACCEPTED);
        IllegalStateException databaseFailure = new IllegalStateException("database failed");
        when(loader.load(MESSAGE)).thenReturn(snapshot);
        when(sandbox.execute(snapshot)).thenReturn(result);
        doThrow(databaseFailure).when(completion).finish(CLAIM, result);

        assertThatThrownBy(() -> runner.run(CLAIM)).isSameAs(databaseFailure);
    }

    private JudgeTaskSnapshot snapshot() {
        return new JudgeTaskSnapshot(
                MESSAGE.taskId(),
                MESSAGE.submissionId(),
                1L,
                "JAVA_21",
                "public class Main { public static void main(String[] args) {} }",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                1000,
                192,
                65536,
                "trim-trailing-whitespace-v1",
                "m0-v1",
                "eclipse-temurin:21-jdk@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                List.of(
                        new JudgeTestCase(
                                1,
                                new byte[0],
                                "ok\n".getBytes(StandardCharsets.UTF_8))));
    }
}
