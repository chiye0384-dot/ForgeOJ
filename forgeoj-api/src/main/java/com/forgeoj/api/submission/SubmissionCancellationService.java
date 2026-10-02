package com.forgeoj.api.submission;

import com.forgeoj.api.observability.CommittedJudgingEvents;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubmissionCancellationService {

    private final SubmissionMapper mapper;
    private final com.forgeoj.api.auth.AccountService accounts;

    public SubmissionCancellationService(SubmissionMapper mapper, com.forgeoj.api.auth.AccountService accounts) {
        this.mapper = mapper;
        this.accounts = accounts;
    }

    @Transactional
    public SubmissionResult cancelForOwner(long userId, String submissionId) {
        accounts.requireCurrentWrite(userId);
        String normalizedId;
        try {
            normalizedId = UUID.fromString(submissionId).toString();
            if (!normalizedId.equalsIgnoreCase(submissionId)) {
                throw notFound();
            }
        } catch (IllegalArgumentException invalidId) {
            throw notFound();
        }
        String taskId = mapper.findTaskIdByOwner(userId, normalizedId)
                .orElseThrow(SubmissionCancellationService::notFound);
        // Start the locking join from the task primary key, matching the Worker claim order.
        SubmissionCancellationRow row = mapper.findCancellationForOwnerForUpdate(userId, taskId)
                .orElseThrow(SubmissionCancellationService::notFound);
        if ("CANCELLED".equals(row.taskStatus()) && "CANCELLED".equals(row.processingStatus())) {
            return new SubmissionResult(row.submissionId(), "CANCELLED", row.submissionVersion());
        }
        if (!"QUEUED".equals(row.taskStatus()) || !"QUEUED".equals(row.processingStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Submission cannot be cancelled");
        }
        if (mapper.cancelQueuedTask(row.taskId(), row.taskVersion()) != 1
                || mapper.cancelQueuedSubmission(row.submissionId(), row.submissionVersion()) != 1) {
            throw new IllegalStateException("Queued cancellation lost state ownership");
        }
        CommittedJudgingEvents.afterCommit("submission.cancelled", row.submissionId(),
                row.taskId(), null, "CANCELLED", row.submissionVersion() + 1);
        return new SubmissionResult(row.submissionId(), "CANCELLED", row.submissionVersion() + 1);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found");
    }
}
