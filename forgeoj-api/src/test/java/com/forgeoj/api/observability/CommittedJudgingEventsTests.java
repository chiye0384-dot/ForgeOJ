package com.forgeoj.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CommittedJudgingEventsTests {

    private final Logger logger = (Logger) LoggerFactory.getLogger(CommittedJudgingEvents.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach void start() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach void stop() {
        logger.detachAppender(logs);
        logs.stop();
        TransactionSynchronizationManager.clear();
        MDC.clear();
    }

    @Test void emitsOnlyAfterCommitAndCapturesRequestId() {
        begin();
        MDC.put("requestId", "internal-request-id");
        register();
        MDC.remove("requestId");
        assertThat(logs.list).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getMDCPropertyMap()).containsEntry("requestId", "internal-request-id");
        assertThat(logs.list.getFirst().getKeyValuePairs()).noneSatisfy(pair -> assertThat(pair.key).isEqualTo("requestId"));
    }

    @Test void rollbackEmitsNoCommittedEvent() {
        begin();
        register();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(logs.list).isEmpty();
    }

    @Test void noTransactionDoesNotClaimDurability() {
        register();
        assertThat(logs.list).isEmpty();
    }

    private void begin() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private void register() {
        CommittedJudgingEvents.afterCommit("submission.created", "submission-id", "task-id", "outbox-id", "QUEUED", 0);
    }
}
