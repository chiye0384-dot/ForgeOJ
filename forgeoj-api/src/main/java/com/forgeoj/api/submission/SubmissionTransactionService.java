package com.forgeoj.api.submission;

import com.forgeoj.api.observability.CommittedJudgingEvents;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class SubmissionTransactionService {

    private static final int MAX_QUEUED_OR_RETRYING_PER_USER = 3;

    private final SubmissionMapper submissionMapper;
    private final com.forgeoj.api.auth.AccountService accounts;

    SubmissionTransactionService(SubmissionMapper submissionMapper, com.forgeoj.api.auth.AccountService accounts) {
        this.submissionMapper = submissionMapper;
        this.accounts = accounts;
    }

    @Transactional
    public SubmissionResult createNew(
            long userId,
            String problemSlug,
            UUID clientRequestId,
            String language,
            String sourceCode,
            String sourceSha256) {
        accounts.requireCurrentWrite(userId);
        if (submissionMapper.lockQuota(userId).isEmpty()) {
            throw new IllegalStateException("User judge quota lock is unavailable");
        }
        if (!submissionMapper.isActiveUser(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is not active");
        }
        // A concurrent caller may have committed this key while we waited for the quota lock.
        var existing = submissionMapper.findResultByRequest(userId, clientRequestId.toString());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (submissionMapper.countQueuedOrRetrying(userId)
                >= MAX_QUEUED_OR_RETRYING_PER_USER) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Queued submission limit reached");
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

        CommittedJudgingEvents.afterCommit(
                "submission.created", submissionId, taskId, eventId, "QUEUED", 0);

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
