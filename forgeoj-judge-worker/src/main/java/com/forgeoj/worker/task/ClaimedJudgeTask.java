package com.forgeoj.worker.task;

import com.forgeoj.worker.messaging.JudgeTaskMessage;

import java.util.Objects;

public record ClaimedJudgeTask(
        JudgeTaskMessage message,
        String attemptId,
        int attemptNo,
        String leaseToken,
        String workerId) {

    public ClaimedJudgeTask {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(workerId, "workerId");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
    }
}
