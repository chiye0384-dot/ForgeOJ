package com.forgeoj.api.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only identifiers and platform-owned state belong here, never request/result objects. */
public final class CommittedJudgingEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommittedJudgingEvents.class);

    private CommittedJudgingEvents() {}

    public static void afterCommit(String event, String submissionId, String judgeTaskId,
            String outboxEventId, String processingStatus, long statusVersion) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        String requestId = MDC.get("requestId");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                String previous = MDC.get("requestId");
                try {
                    if (requestId == null) MDC.remove("requestId");
                    else MDC.put("requestId", requestId);
                    var log = LOGGER.atInfo().addKeyValue("event", event)
                            .addKeyValue("submissionId", submissionId)
                            .addKeyValue("judgeTaskId", judgeTaskId)
                            .addKeyValue("processingStatus", processingStatus)
                            .addKeyValue("statusVersion", statusVersion);
                    if (outboxEventId != null) log.addKeyValue("outboxEventId", outboxEventId);
                    log.log("Judging state committed");
                } catch (RuntimeException loggingFailure) {
                    // Do not turn an already committed write into a failed response.
                } finally {
                    if (previous == null) MDC.remove("requestId");
                    else MDC.put("requestId", previous);
                }
            }
        });
    }
}
