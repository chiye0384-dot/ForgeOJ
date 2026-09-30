package com.forgeoj.api.submission;

record SubmissionCancellationRow(
        String taskId,
        String submissionId,
        String taskStatus,
        String processingStatus,
        long taskVersion,
        long submissionVersion) {}
