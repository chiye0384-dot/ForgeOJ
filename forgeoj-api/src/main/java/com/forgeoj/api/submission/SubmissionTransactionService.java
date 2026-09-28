package com.forgeoj.api.submission;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class SubmissionTransactionService {

    private final SubmissionMapper submissionMapper;

    SubmissionTransactionService(SubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
    }

    @Transactional
    public SubmissionResult createNew(
            long userId,
            String problemSlug,
            UUID clientRequestId,
            String language,
            String sourceCode,
            String sourceSha256) {
        if (!submissionMapper.isActiveUser(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is not active");
        }

        JudgeVersionSnapshot judgeVersion =
                submissionMapper
                        .findActiveJudgeVersion(problemSlug)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Problem not found"));

        String submissionId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        submissionMapper.insertSubmission(
                new NewSubmissionRow(
                        submissionId,
                        userId,
                        judgeVersion.problemId(),
                        judgeVersion.judgeVersionId(),
                        clientRequestId.toString(),
                        language,
                        sourceCode,
                        sourceSha256,
                        judgeVersion.timeLimitMs(),
                        judgeVersion.memoryLimitMb(),
                        judgeVersion.outputLimitBytes(),
                        judgeVersion.comparisonRuleVersion(),
                        judgeVersion.sandboxPolicyVersion(),
                        judgeVersion.javaImageDigest(),
                        judgeVersion.testDatasetSha256()));
        submissionMapper.insertJudgeTask(taskId, submissionId);
        submissionMapper.insertOutboxEvent(eventId, taskId, taskPayload(taskId, submissionId));

        return new SubmissionResult(submissionId, "QUEUED", 0);
    }

    private String taskPayload(String taskId, String submissionId) {
        return """
                {"taskId":"%s","submissionId":"%s","taskType":"JUDGE_SUBMISSION","contractVersion":1}
                """
                .formatted(taskId, submissionId)
                .strip();
    }
}
