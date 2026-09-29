package com.forgeoj.worker.snapshot;

import java.util.List;
import java.util.Objects;

public record JudgeTaskSnapshot(
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
        List<JudgeTestCase> testCases) {

    public JudgeTaskSnapshot {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(submissionId, "submissionId");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(sourceCode, "sourceCode");
        Objects.requireNonNull(sourceSha256, "sourceSha256");
        Objects.requireNonNull(comparisonRuleVersion, "comparisonRuleVersion");
        Objects.requireNonNull(sandboxPolicyVersion, "sandboxPolicyVersion");
        Objects.requireNonNull(javaImageDigest, "javaImageDigest");
        Objects.requireNonNull(testDatasetSha256, "testDatasetSha256");
        testCases = List.copyOf(testCases);
    }

}
