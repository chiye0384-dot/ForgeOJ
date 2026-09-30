package com.forgeoj.worker.task;

import com.forgeoj.worker.messaging.JudgeTaskMessage;
import com.forgeoj.worker.sandbox.SandboxExecutionResult;
import com.forgeoj.worker.sandbox.SandboxOutcome;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JudgeTaskCompletionService {

    private static final int DIAGNOSTIC_LIMIT = 2000;
    private static final String SYSTEM_ERROR_DIAGNOSTIC = "Judging infrastructure failed";

    private final JudgeTaskMapper mapper;

    JudgeTaskCompletionService(JudgeTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void finish(ClaimedJudgeTask claimedTask, SandboxExecutionResult result) {
        String verdict = verdict(result.outcome());
        String diagnostic =
                result.outcome() == SandboxOutcome.COMPILE_ERROR
                        ? safeDiagnostic(result.diagnosticMessage())
                        : null;
        complete(claimedTask, "FINISHED", verdict, diagnostic, "SUCCEEDED", null);
    }

    @Transactional
    public void failSystem(ClaimedJudgeTask claimedTask) {
        complete(
                claimedTask,
                "SYSTEM_ERROR",
                null,
                SYSTEM_ERROR_DIAGNOSTIC,
                "DEAD_LETTERED",
                "PLATFORM_FAILURE");
    }

    private void complete(
            ClaimedJudgeTask claimedTask,
            String terminalStatus,
            String verdict,
            String diagnosticMessage,
            String attemptStatus,
            String failureCode) {
        JudgeTaskMessage message = claimedTask.message();
        JudgeTaskClaimRow row =
                mapper.findForUpdate(message.taskId())
                        .orElseThrow(() -> new IllegalStateException("Judge task does not exist"));
        if (!matchesRunningTask(row, claimedTask)) {
            throw new IllegalStateException("Judge task is not the matching running task");
        }

        int taskUpdates =
                mapper.markTaskTerminal(
                        row.taskId(),
                        row.submissionId(),
                        row.taskStatusVersion(),
                        claimedTask.leaseToken(),
                        terminalStatus);
        int submissionUpdates =
                mapper.markSubmissionTerminal(
                        row.submissionId(),
                        row.submissionStatusVersion(),
                        terminalStatus,
                        verdict,
                        diagnosticMessage);
        int attemptUpdates =
                mapper.markAttemptTerminal(
                        claimedTask.attemptId(),
                        row.taskId(),
                        claimedTask.leaseToken(),
                        attemptStatus,
                        failureCode,
                        failureCode == null ? null : SYSTEM_ERROR_DIAGNOSTIC);
        if (taskUpdates != 1 || submissionUpdates != 1 || attemptUpdates != 1) {
            throw new IllegalStateException("Judge task completion lost its compare-and-swap");
        }
    }

    private boolean matchesRunningTask(JudgeTaskClaimRow row, ClaimedJudgeTask claimedTask) {
        JudgeTaskMessage message = claimedTask.message();
        return row.taskId().equals(message.taskId())
                && row.submissionId().equals(message.submissionId())
                && row.taskType().equals(message.taskType())
                && row.contractVersion() == message.contractVersion()
                && "RUNNING".equals(row.taskStatus())
                && "RUNNING".equals(row.submissionStatus())
                && claimedTask.leaseToken().equals(row.leaseToken());
    }

    private String verdict(SandboxOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED -> "AC";
            case WRONG_ANSWER -> "WA";
            case COMPILE_ERROR -> "CE";
            case RUNTIME_ERROR -> "RE";
            case TIME_LIMIT_EXCEEDED -> "TLE";
            case OUTPUT_LIMIT_EXCEEDED -> "OLE";
        };
    }

    private String safeDiagnostic(String diagnosticMessage) {
        if (diagnosticMessage == null || diagnosticMessage.isBlank()) {
            return null;
        }
        String withoutNulls = diagnosticMessage.replace("\0", "");
        return withoutNulls.length() <= DIAGNOSTIC_LIMIT
                ? withoutNulls
                : withoutNulls.substring(0, DIAGNOSTIC_LIMIT);
    }
}
