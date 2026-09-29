package com.forgeoj.worker.task;

import com.forgeoj.worker.messaging.JudgeTaskMessage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JudgeTaskClaimService {

    private final JudgeTaskMapper mapper;

    JudgeTaskClaimService(JudgeTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public TaskClaimOutcome claim(JudgeTaskMessage message) {
        JudgeTaskClaimRow row = mapper.findForUpdate(message.taskId()).orElse(null);
        if (row == null || !matchesContract(row, message)) {
            return TaskClaimOutcome.REJECTED;
        }

        if ("QUEUED".equals(row.taskStatus()) && "QUEUED".equals(row.submissionStatus())) {
            int taskUpdates =
                    mapper.markTaskRunning(
                            row.taskId(), row.submissionId(), row.taskStatusVersion());
            int submissionUpdates =
                    mapper.markSubmissionRunning(
                            row.submissionId(), row.submissionStatusVersion());
            if (taskUpdates != 1 || submissionUpdates != 1) {
                throw new IllegalStateException("Judge task claim lost its compare-and-swap");
            }
            return TaskClaimOutcome.CLAIMED;
        }

        if (row.taskStatus().equals(row.submissionStatus())
                && ("RUNNING".equals(row.taskStatus())
                        || "FINISHED".equals(row.taskStatus())
                        || "SYSTEM_ERROR".equals(row.taskStatus()))) {
            return TaskClaimOutcome.DUPLICATE;
        }
        return TaskClaimOutcome.REJECTED;
    }

    private boolean matchesContract(JudgeTaskClaimRow row, JudgeTaskMessage message) {
        return row.taskId().equals(message.taskId())
                && row.submissionId().equals(message.submissionId())
                && row.taskType().equals(message.taskType())
                && row.contractVersion() == message.contractVersion();
    }
}
