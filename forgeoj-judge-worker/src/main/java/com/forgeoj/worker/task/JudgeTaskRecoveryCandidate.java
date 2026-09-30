package com.forgeoj.worker.task;

record JudgeTaskRecoveryCandidate(
        String taskId, String submissionId, String taskType, int contractVersion) {}
