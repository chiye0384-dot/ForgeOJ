package com.forgeoj.worker.task;

import com.forgeoj.worker.messaging.JudgeTaskMessage;
import com.forgeoj.worker.sandbox.SandboxExecutionResult;
import com.forgeoj.worker.sandbox.SandboxOutcome;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JudgeTaskCompletionService {

    private static final int DIAGNOSTIC_LIMIT = 2000;
    private static final String SYSTEM_ERROR_DIAGNOSTIC = "Judging infrastructure failed";
    private static final String PLATFORM_FAILURE_CODE = "PLATFORM_FAILURE";
    private static final String SNAPSHOT_INVALID_CODE = "SNAPSHOT_INVALID";

    private final JudgeTaskMapper mapper;
    private final RetryBackoffPolicy retryBackoffPolicy;

    JudgeTaskCompletionService(
            JudgeTaskMapper mapper, RetryBackoffPolicy retryBackoffPolicy) {
        this.mapper = mapper;
        this.retryBackoffPolicy = retryBackoffPolicy;
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
    public void recordPlatformFailure(ClaimedJudgeTask claimedTask) {
        JudgeTaskClaimRow row = lockedMatchingRunningTask(claimedTask);
        if (row.attemptCount() < row.maxAttempts()) {
            scheduleRetry(claimedTask, row);
            return;
        }
        deadLetter(claimedTask, row, PLATFORM_FAILURE_CODE);
    }

    @Transactional
    public void recordUnrecoverablePlatformFailure(ClaimedJudgeTask claimedTask) {
        JudgeTaskClaimRow row = lockedMatchingRunningTask(claimedTask);
        deadLetter(claimedTask, row, SNAPSHOT_INVALID_CODE);
    }

    private void complete(
            ClaimedJudgeTask claimedTask,
            String terminalStatus,
            String verdict,
            String diagnosticMessage,
            String attemptStatus,
            String failureCode) {
        JudgeTaskClaimRow row = lockedMatchingRunningTask(claimedTask);

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

    private JudgeTaskClaimRow lockedMatchingRunningTask(ClaimedJudgeTask claimedTask) {
        JudgeTaskMessage message = claimedTask.message();
        JudgeTaskClaimRow row =
                mapper.findForUpdate(message.taskId())
                        .orElseThrow(() -> new IllegalStateException("Judge task does not exist"));
        if (!matchesRunningTask(row, claimedTask)) {
            throw new IllegalStateException("Judge task is not the matching running task");
        }
        return row;
    }

    private void scheduleRetry(ClaimedJudgeTask claimedTask, JudgeTaskClaimRow row) {
        long delaySeconds = retryBackoffPolicy.delaySeconds(claimedTask.attemptNo());
        int taskUpdates =
                mapper.markTaskRetrying(
                        row.taskId(),
                        row.submissionId(),
                        row.taskStatusVersion(),
                        claimedTask.leaseToken(),
                        delaySeconds,
                        PLATFORM_FAILURE_CODE,
                        SYSTEM_ERROR_DIAGNOSTIC);
        int submissionUpdates =
                mapper.markSubmissionRetrying(
                        row.submissionId(), row.submissionStatusVersion());
        int attemptUpdates =
                mapper.markAttemptTerminal(
                        claimedTask.attemptId(),
                        row.taskId(),
                        claimedTask.leaseToken(),
                        "RETRYABLE_FAILURE",
                        PLATFORM_FAILURE_CODE,
                        SYSTEM_ERROR_DIAGNOSTIC);
        int outboxInserts =
                mapper.insertOutboxEvent(
                        UUID.randomUUID().toString(),
                        row.taskId(),
                        row.submissionId(),
                        "JUDGE_TASK_QUEUED",
                        claimedTask.attemptNo(),
                        delaySeconds);
        requireAllWritten(taskUpdates, submissionUpdates, attemptUpdates, outboxInserts);
    }

    private void deadLetter(
            ClaimedJudgeTask claimedTask, JudgeTaskClaimRow row, String failureCode) {
        int taskUpdates =
                mapper.markTaskDeadLetter(
                        row.taskId(),
                        row.submissionId(),
                        row.taskStatusVersion(),
                        claimedTask.leaseToken(),
                        failureCode,
                        SYSTEM_ERROR_DIAGNOSTIC);
        int submissionUpdates =
                mapper.markSubmissionTerminal(
                        row.submissionId(),
                        row.submissionStatusVersion(),
                        "SYSTEM_ERROR",
                        null,
                        SYSTEM_ERROR_DIAGNOSTIC);
        int attemptUpdates =
                mapper.markAttemptTerminal(
                        claimedTask.attemptId(),
                        row.taskId(),
                        claimedTask.leaseToken(),
                        "DEAD_LETTERED",
                        failureCode,
                        SYSTEM_ERROR_DIAGNOSTIC);
        int outboxInserts =
                mapper.insertOutboxEvent(
                        UUID.randomUUID().toString(),
                        row.taskId(),
                        row.submissionId(),
                        "JUDGE_TASK_DEAD_LETTERED",
                        claimedTask.attemptNo(),
                        0);
        requireAllWritten(taskUpdates, submissionUpdates, attemptUpdates, outboxInserts);
    }

    private void requireAllWritten(int... updates) {
        for (int update : updates) {
            if (update != 1) {
                throw new IllegalStateException("Judge task transition lost its compare-and-swap");
            }
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
