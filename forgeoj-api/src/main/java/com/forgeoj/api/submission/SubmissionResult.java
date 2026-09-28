package com.forgeoj.api.submission;

public record SubmissionResult(
        String submissionId, String processingStatus, long statusVersion) {}
