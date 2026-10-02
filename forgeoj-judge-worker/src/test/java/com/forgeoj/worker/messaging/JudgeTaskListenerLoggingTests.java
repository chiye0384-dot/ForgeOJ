package com.forgeoj.worker.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.forgeoj.worker.observability.JudgingEvents;
import com.forgeoj.worker.task.ClaimedJudgeTask;
import com.forgeoj.worker.task.JudgeTaskClaimService;
import com.forgeoj.worker.task.TaskClaimOutcome;
import com.forgeoj.worker.task.TaskClaimResult;
import com.rabbitmq.client.Channel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import tools.jackson.databind.json.JsonMapper;

class JudgeTaskListenerLoggingTests {

    private final JudgeTaskClaimService claims = mock(JudgeTaskClaimService.class);
    private final JudgeTaskRunner runner = mock(JudgeTaskRunner.class);
    private final Channel channel = mock(Channel.class);
    private final JudgeTaskListener listener = new JudgeTaskListener(new JudgeTaskMessageParser(JsonMapper.builder().build()), claims, runner);
    private final Logger logger = (Logger) LoggerFactory.getLogger(JudgingEvents.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach void start() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach void stop() {
        logger.detachAppender(logs);
        logs.stop();
        MDC.clear();
    }

    @Test void reusedThreadCarriesOnlyTheCurrentValidatedIds() throws Exception {
        for (int i = 0; i < 2; i++) {
            ClaimedJudgeTask task = task();
            when(claims.claim(task.message())).thenReturn(new TaskClaimResult(TaskClaimOutcome.CLAIMED, task));
            logs.list.clear();
            listener.consumeSubmission(inbound(task.message()), channel);
            assertThat(logs.list).hasSize(3).allSatisfy(log -> {
                assertThat(fields(log)).containsEntry("submissionId", task.message().submissionId())
                        .containsEntry("judgeTaskId", task.message().taskId());
                assertThat(fields(log).toString()).doesNotContain("SECRET_SENTINEL");
            });
            assertThat(fields(logs.list.getLast())).containsEntry("event", "delivery.ack_sent")
                    .containsEntry("attemptId", task.attemptId()).containsEntry("attemptNo", 1);
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }
    }

    @Test void invalidBodyAndAmqpHeadersAreNeverLogged() throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("Authorization", "SECRET_SENTINEL");
        listener.consumeSubmission(new Message("SECRET_SENTINEL".getBytes(StandardCharsets.UTF_8), properties), channel);
        assertThat(logs.list).hasSize(1);
        assertThat(fields(logs.list.getFirst())).containsOnlyKeys("event", "failureCode")
                .containsEntry("failureCode", "INVALID_MESSAGE");
        verify(channel).basicReject(0, false);
    }

    @Test void claimFailureLogsFixedCodeAndPreservesRequeue() throws Exception {
        when(claims.claim(any())).thenThrow(new IllegalStateException("SECRET_SENTINEL"));
        listener.consumeSubmission(inbound(task().message()), channel);
        assertSafeFailure("CLAIM_FAILURE");
        verify(channel).basicNack(0, false, true);
    }

    @Test void executionFailureDoesNotLogLeaseTokenOrRawThrowable() throws Exception {
        ClaimedJudgeTask task = task();
        when(claims.claim(any())).thenReturn(new TaskClaimResult(TaskClaimOutcome.CLAIMED, task));
        doThrow(new IllegalStateException("SECRET_SENTINEL")).when(runner).run(task);
        listener.consumeSubmission(inbound(task.message()), channel);
        assertSafeFailure("EXECUTION_FAILURE");
        verify(channel).basicReject(0, false);
    }

    @Test void ackFailureNeverClaimsAckSucceeded() throws Exception {
        ClaimedJudgeTask task = task();
        when(claims.claim(any())).thenReturn(new TaskClaimResult(TaskClaimOutcome.CLAIMED, task));
        doThrow(new IOException("SECRET_SENTINEL")).when(channel).basicAck(0, false);
        assertThatThrownBy(() -> listener.consumeSubmission(inbound(task.message()), channel)).isInstanceOf(IOException.class);
        assertSafeFailure("ACK_FAILURE");
        assertThat(logs.list).noneSatisfy(log -> assertThat(fields(log)).containsEntry("event", "delivery.ack_sent"));
    }

    private void assertSafeFailure(String code) {
        assertThat(fields(logs.list.getLast())).containsEntry("failureCode", code);
        assertThat(logs.list).allSatisfy(log -> {
            assertThat(fields(log).toString()).doesNotContain("SECRET_SENTINEL");
            assertThat(log.getFormattedMessage()).doesNotContain("SECRET_SENTINEL");
            assertThat(log.getThrowableProxy()).isNull();
        });
    }

    @Test void runtimeAckFailureRetainsClaimedDeliveryRejection() throws Exception {
        ClaimedJudgeTask task = task();
        when(claims.claim(any())).thenReturn(new TaskClaimResult(TaskClaimOutcome.CLAIMED, task));
        doThrow(new IllegalStateException("SECRET_SENTINEL")).when(channel).basicAck(0, false);
        listener.consumeSubmission(inbound(task.message()), channel);
        assertSafeFailure("ACK_FAILURE");
        verify(channel).basicReject(0, false);
        assertThat(logs.list).noneSatisfy(log -> assertThat(fields(log)).containsEntry("event", "delivery.ack_sent"));
    }

    private ClaimedJudgeTask task() {
        return new ClaimedJudgeTask(new JudgeTaskMessage(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "JUDGE_SUBMISSION", 1),
                UUID.randomUUID().toString(), 1, "LEASE_SECRET_SENTINEL", "WORKER_SECRET_SENTINEL");
    }

    private Message inbound(JudgeTaskMessage message) {
        String body = "{\"taskId\":\"%s\",\"submissionId\":\"%s\",\"taskType\":\"JUDGE_SUBMISSION\",\"contractVersion\":1}"
                .formatted(message.taskId(), message.submissionId());
        MessageProperties properties = new MessageProperties();
        properties.setHeader("Cookie", "SECRET_SENTINEL");
        properties.setMessageId("SECRET_SENTINEL");
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
