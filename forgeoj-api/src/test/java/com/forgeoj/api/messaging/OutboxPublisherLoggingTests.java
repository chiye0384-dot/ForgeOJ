package com.forgeoj.api.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class OutboxPublisherLoggingTests {

    @Test
    void brokerNackReasonNeverEntersApplicationLogs() {
        OutboxMapper mapper = mock(OutboxMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        OutboxEventRow event = new OutboxEventRow(
                UUID.randomUUID().toString(), "JUDGE_TASK_QUEUED", 0,
                "SOURCE_PASSWORD_HIDDEN_INPUT_SENTINEL", 0,
                UUID.randomUUID().toString(), UUID.randomUUID().toString());
        when(mapper.findPending(20)).thenReturn(List.of(event));
        when(mapper.recordPublishFailure(event.id(), 0, 3, 1, "BROKER_NACK")).thenReturn(1);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(
                    false, "SOURCE_PASSWORD_HIDDEN_INPUT_SENTINEL"));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        Logger logger = (Logger) LoggerFactory.getLogger(OutboxPublisher.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThat(new OutboxPublisher(mapper, rabbit, false, 20, 100, 3, 1, 4)
                    .publishPending()).isZero();
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getKeyValuePairs())
                    .anySatisfy(pair -> {
                        assertThat(pair.key).isEqualTo("failureCode");
                        assertThat(pair.value).isEqualTo("BROKER_NACK");
                    });
            assertThat(logs.list).allSatisfy(log -> {
                assertThat(log.getFormattedMessage()).doesNotContain("SENTINEL");
                assertThat(log.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
