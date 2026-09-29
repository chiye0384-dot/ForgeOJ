package com.forgeoj.worker.task;

record JudgeTaskClaimRow(
        String taskId,
        String submissionId,
        String taskType,
        int contractVersion,
        String taskStatus,
        long taskStatusVersion,
        String submissionStatus,
        long submissionStatusVersion) {}
