package com.forgeoj.worker.observability;

import com.forgeoj.worker.messaging.JudgeTaskMessage;
import com.forgeoj.worker.task.ClaimedJudgeTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit identifiers avoid leaking ownership across reused consumer/heartbeat threads. */
public final class JudgingEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(JudgingEvents.class);

    private JudgingEvents() {}

    public static void record(String event, JudgeTaskMessage message, ClaimedJudgeTask attempt,
            String outcome, String failureCode) {
        try {
            var log = (failureCode == null ? LOGGER.atInfo()
                    : "EXECUTION_FAILURE".equals(failureCode) ? LOGGER.atError() : LOGGER.atWarn())
                    .addKeyValue("event", event);
            if (message != null) {
                log.addKeyValue("judgeTaskId", message.taskId())
                        .addKeyValue("submissionId", message.submissionId());
            }
            if (attempt != null) {
                log.addKeyValue("attemptId", attempt.attemptId())
                        .addKeyValue("attemptNo", attempt.attemptNo());
            }
            if (outcome != null) log.addKeyValue("outcome", outcome);
            if (failureCode != null) log.addKeyValue("failureCode", failureCode);
            log.log("Judging lifecycle event");
        } catch (RuntimeException loggingFailure) {
            // Never change execution/ACK semantics because a logging sink failed.
        }
    }

    public static void afterCommit(String event, JudgeTaskMessage message,
            ClaimedJudgeTask attempt, String outcome, String failureCode) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                record(event, message, attempt, outcome, failureCode);
            }
        });
    }
}
