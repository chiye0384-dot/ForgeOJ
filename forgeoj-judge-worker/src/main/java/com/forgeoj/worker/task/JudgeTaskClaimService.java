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
    private final long quotaRetryDelaySeconds;

    JudgeTaskClaimService(
            JudgeTaskMapper mapper,
            @Value("${forgeoj.worker.instance-id:${random.uuid}}") String workerId,
            @Value("${forgeoj.worker.lease-duration-seconds:30}") long leaseDurationSeconds,
            @Value("${forgeoj.worker.quota-retry-delay-seconds:5}")
                    long quotaRetryDelaySeconds) {
        this.mapper = mapper;
        this.workerId = workerId;
        if (leaseDurationSeconds < 1) {
            throw new IllegalArgumentException("Worker lease duration must be positive");
        }
        this.leaseDurationSeconds = leaseDurationSeconds;
        if (quotaRetryDelaySeconds < 1) {
            throw new IllegalArgumentException("Worker quota retry delay must be positive");
        }
        this.quotaRetryDelaySeconds = quotaRetryDelaySeconds;
    }

    @Transactional
    public TaskClaimResult claim(JudgeTaskMessage message) {
        JudgeTaskClaimRow row = mapper.findForUpdate(message.taskId()).orElse(null);
        if (row == null || !matchesContract(row, message)) {
            return TaskClaimResult.withoutOwnership(TaskClaimOutcome.REJECTED);
        }

        if (claimable(row)) {
            if (mapper.lockUserQuota(row.userId()).isEmpty()) {
                throw new IllegalStateException("User judge quota lock is unavailable");
            }
            if (mapper.countOtherRunningSubmissions(row.userId(), row.submissionId()) >= 1) {
                if (mapper.deferForUserQuota(
                                row.taskId(),
                                row.submissionId(),
                                row.taskStatus(),
                                row.taskStatusVersion(),
                                quotaRetryDelaySeconds)
                        != 1) {
                    throw new IllegalStateException("Judge task quota deferral lost its state");
                }
                return TaskClaimResult.withoutOwnership(TaskClaimOutcome.DEFERRED);
            }
            return claimExecution(row, message);
        }

        if ((row.taskStatus().equals(row.submissionStatus())
                && DUPLICATE_STATUSES.contains(row.taskStatus()))
                || ("WAITING_RETRY".equals(row.taskStatus())
                        && "RUNNING".equals(row.submissionStatus()))) {
            return TaskClaimResult.withoutOwnership(TaskClaimOutcome.DUPLICATE);
        }
        return TaskClaimResult.withoutOwnership(TaskClaimOutcome.REJECTED);
    }

    private TaskClaimResult claimExecution(JudgeTaskClaimRow row, JudgeTaskMessage message) {
        if (row.attemptCount() >= row.maxAttempts()) {
            deadLetterExhausted(row);
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

    private void deadLetterExhausted(JudgeTaskClaimRow row) {
        int attemptUpdates = 1;
        if ("RUNNING".equals(row.taskStatus())) {
            if (row.leaseToken() == null) {
                throw new IllegalStateException("Expired judge task has no lease token");
            }
            attemptUpdates =
                    mapper.markExpiredAttemptDeadLettered(row.taskId(), row.leaseToken());
        }
        int taskUpdates =
                mapper.markTaskExhausted(
                        row.taskId(),
                        row.submissionId(),
                        row.taskStatus(),
                        row.taskStatusVersion());
        int submissionUpdates =
                mapper.markSubmissionSystemError(
                        row.submissionId(),
                        row.submissionStatus(),
                        row.submissionStatusVersion());
        int outboxInserts =
                mapper.insertOutboxEvent(
                        UUID.randomUUID().toString(),
                        row.taskId(),
                        row.submissionId(),
                        "JUDGE_TASK_DEAD_LETTERED",
                        row.attemptCount(),
                        0);
        if (attemptUpdates != 1
                || taskUpdates != 1
                || submissionUpdates != 1
                || outboxInserts != 1) {
            throw new IllegalStateException("Exhausted judge task transition was not atomic");
        }
    }

    private boolean claimable(JudgeTaskClaimRow row) {
        if ("WAITING_RETRY".equals(row.taskStatus())
                && "RUNNING".equals(row.submissionStatus())) {
            return row.retryDue();
        }
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
