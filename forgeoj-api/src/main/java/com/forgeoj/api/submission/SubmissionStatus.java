package com.forgeoj.api.submission;

public record SubmissionStatus(
        String submissionId,
        String processingStatus,
        long statusVersion,
        String verdict,
        String diagnosticMessage) {}
