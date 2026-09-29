package com.forgeoj.worker.snapshot;

record JudgeTaskSnapshotRow(
        String taskId,
        String submissionId,
        long judgeVersionId,
        String language,
        String sourceCode,
        String sourceSha256,
        int timeLimitMs,
        int memoryLimitMb,
        long outputLimitBytes,
        String comparisonRuleVersion,
        String sandboxPolicyVersion,
        String javaImageDigest,
        String testDatasetSha256,
        String storedTestDatasetSha256) {}
