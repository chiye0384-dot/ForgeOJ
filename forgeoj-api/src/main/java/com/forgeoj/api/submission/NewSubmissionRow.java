package com.forgeoj.api.submission;

record NewSubmissionRow(
        String id,
        long userId,
        long problemId,
        long judgeVersionId,
        String clientRequestId,
        String language,
        String sourceCode,
        String sourceSha256,
        int timeLimitMs,
        int memoryLimitMb,
        long outputLimitBytes,
        String comparisonRuleVersion,
        String sandboxPolicyVersion,
        String javaImageDigest,
        String testDatasetSha256) {}
