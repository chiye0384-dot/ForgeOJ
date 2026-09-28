package com.forgeoj.api.submission;

record JudgeVersionSnapshot(
        long problemId,
        long judgeVersionId,
        int timeLimitMs,
        int memoryLimitMb,
        long outputLimitBytes,
        String comparisonRuleVersion,
        String sandboxPolicyVersion,
        String javaImageDigest,
        String testDatasetSha256) {}
