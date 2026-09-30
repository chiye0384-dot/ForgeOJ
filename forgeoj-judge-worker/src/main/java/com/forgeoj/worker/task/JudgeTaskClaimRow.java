package com.forgeoj.worker.task;

record JudgeTaskClaimRow(
        String taskId,
        String submissionId,
        String taskType,
        int contractVersion,
        String taskStatus,
        long taskStatusVersion,
        int attemptCount,
        int maxAttempts,
        String leaseToken,
        boolean leaseExpired,
        boolean retryDue,
        String submissionStatus,
        long submissionStatusVersion) {}
