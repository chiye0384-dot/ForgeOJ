package com.forgeoj.worker.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.forgeoj.worker.messaging.JudgeTaskMessage;
import com.forgeoj.worker.task.ClaimedJudgeTask;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JudgingEventsTests {

    private final Logger logger = (Logger) LoggerFactory.getLogger(JudgingEvents.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach void start() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach void stop() {
        logger.detachAppender(logs);
        logs.stop();
        TransactionSynchronizationManager.clear();
    }

    @Test void committedAttemptUsesExplicitSafeFields() {
        begin();
        register();
        assertThat(logs.list).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getKeyValuePairs()).allSatisfy(pair ->
                assertThat(String.valueOf(pair.value)).doesNotContain("SECRET_SENTINEL"));
        assertThat(logs.list.getFirst().getKeyValuePairs()).extracting(pair -> pair.key)
                .containsExactly("event", "judgeTaskId", "submissionId", "attemptId", "attemptNo", "outcome");
    }

    @Test void rollbackNeverEmitsFinishedEvent() {
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
        var message = new JudgeTaskMessage("task-id", "submission-id", "JUDGE_SUBMISSION", 1);
        var attempt = new ClaimedJudgeTask(message, "attempt-id", 1, "LEASE_SECRET_SENTINEL", "WORKER_SECRET_SENTINEL");
        JudgingEvents.afterCommit("attempt.finished", message, attempt, "AC", null);
    }
}
