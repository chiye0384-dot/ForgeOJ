package com.forgeoj.worker.task;

import com.forgeoj.worker.messaging.JudgeTaskMessage;

import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JudgeTaskClaimService {

    private static final Set<String> DUPLICATE_STATUSES =
            Set.of("RUNNING", "RETRYING", "FINISHED", "CANCELLED", "SYSTEM_ERROR", "DEAD_LETTER");

    private final JudgeTaskMapper mapper;
    private final String workerId;
    private final long leaseDurationSeconds;

    JudgeTaskClaimService(
            JudgeTaskMapper mapper,
            @Value("${forgeoj.worker.instance-id:${random.uuid}}") String workerId,
            @Value("${forgeoj.worker.lease-duration-seconds:30}") long leaseDurationSeconds) {
        this.mapper = mapper;
        this.workerId = workerId;
        if (leaseDurationSeconds < 1) {
            throw new IllegalArgumentException("Worker lease duration must be positive");
        }
        this.leaseDurationSeconds = leaseDurationSeconds;
    }

    @Transactional
    public TaskClaimResult claim(JudgeTaskMessage message) {
        JudgeTaskClaimRow row = mapper.findForUpdate(message.taskId()).orElse(null);
        if (row == null || !matchesContract(row, message)) {
            return TaskClaimResult.withoutOwnership(TaskClaimOutcome.REJECTED);
        }

        if (claimable(row)) {
            return claimExecution(row, message);
        }

        if (row.taskStatus().equals(row.submissionStatus())
                && DUPLICATE_STATUSES.contains(row.taskStatus())) {
            return TaskClaimResult.withoutOwnership(TaskClaimOutcome.DUPLICATE);
        }
        return TaskClaimResult.withoutOwnership(TaskClaimOutcome.REJECTED);
    }

    private TaskClaimResult claimExecution(JudgeTaskClaimRow row, JudgeTaskMessage message) {
        if (row.attemptCount() >= row.maxAttempts()) {
            return TaskClaimResult.withoutOwnership(TaskClaimOutcome.EXHAUSTED);
        }

        if ("RUNNING".equals(row.taskStatus())) {
            if (row.leaseToken() == null
                    || mapper.markAttemptLeaseExpired(row.taskId(), row.leaseToken()) != 1) {
                throw new IllegalStateException("Expired judge task has no matching active attempt");
            }
        }

        String attemptId = UUID.randomUUID().toString();
        String leaseToken = UUID.randomUUID().toString();
        int taskUpdates =
                mapper.markTaskRunning(
                        row.taskId(),
                        row.submissionId(),
                        row.taskStatusVersion(),
                        row.taskStatus(),
                        workerId,
                        leaseToken,
                        leaseDurationSeconds);
        int submissionUpdates =
                mapper.markSubmissionRunning(
                        row.submissionId(), row.submissionStatusVersion(), row.submissionStatus());
        int attemptInserts =
                mapper.insertAttempt(attemptId, row.taskId(), row.submissionId(), leaseToken);
        if (taskUpdates != 1 || submissionUpdates != 1 || attemptInserts != 1) {
            throw new IllegalStateException("Judge task claim lost its compare-and-swap");
        }
        return TaskClaimResult.claimed(
                new ClaimedJudgeTask(
                        message, attemptId, row.attemptCount() + 1, leaseToken, workerId));
    }

    private boolean claimable(JudgeTaskClaimRow row) {
        if (!row.taskStatus().equals(row.submissionStatus())) {
            return false;
        }
        return switch (row.taskStatus()) {
            case "QUEUED" -> true;
            case "RETRYING" -> row.retryDue();
            case "RUNNING" -> row.leaseExpired();
            default -> false;
        };
    }

    private boolean matchesContract(JudgeTaskClaimRow row, JudgeTaskMessage message) {
        return row.taskId().equals(message.taskId())
                && row.submissionId().equals(message.submissionId())
                && row.taskType().equals(message.taskType())
                && row.contractVersion() == message.contractVersion();
    }
}
