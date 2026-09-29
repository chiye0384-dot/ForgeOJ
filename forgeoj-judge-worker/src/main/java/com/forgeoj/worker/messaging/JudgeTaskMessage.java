package com.forgeoj.worker.messaging;

public record JudgeTaskMessage(
        String taskId, String submissionId, String taskType, int contractVersion) {}
