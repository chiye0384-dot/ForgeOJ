package com.forgeoj.api.messaging;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final boolean scheduledPublishingEnabled;
    private final int batchSize;
    private final long confirmTimeoutMs;
    private final int maximumAttempts;
    private final long baseDelaySeconds;
    private final long maximumDelaySeconds;

    public OutboxPublisher(
            OutboxMapper outboxMapper,
            RabbitTemplate rabbitTemplate,
            @Value("${forgeoj.outbox.publisher.enabled:false}")
                    boolean scheduledPublishingEnabled,
            @Value("${forgeoj.outbox.publisher.batch-size:20}") int batchSize,
            @Value("${forgeoj.outbox.publisher.confirm-timeout-ms:5000}")
                    long confirmTimeoutMs,
            @Value("${forgeoj.outbox.publisher.max-attempts:5}") int maximumAttempts,
            @Value("${forgeoj.outbox.publisher.base-delay-seconds:1}")
                    long baseDelaySeconds,
            @Value("${forgeoj.outbox.publisher.max-delay-seconds:60}")
                    long maximumDelaySeconds) {
        if (batchSize < 1
                || confirmTimeoutMs < 1
                || maximumAttempts < 1
                || baseDelaySeconds < 1
                || maximumDelaySeconds < baseDelaySeconds) {
            throw new IllegalArgumentException("Outbox publisher settings are invalid");
        }
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.scheduledPublishingEnabled = scheduledPublishingEnabled;
        this.batchSize = batchSize;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.maximumAttempts = maximumAttempts;
        this.baseDelaySeconds = baseDelaySeconds;
        this.maximumDelaySeconds = maximumDelaySeconds;
    }

    @Scheduled(fixedDelayString = "${forgeoj.outbox.publisher.fixed-delay-ms:1000}")
    void scheduledPublish() {
        if (scheduledPublishingEnabled) {
            publishPending();
        }
    }

    public int publishPending() {
        int published = 0;
        for (OutboxEventRow event : outboxMapper.findPending(batchSize)) {
            PublishAttempt attempt = publishConfirmed(event);
            if (!attempt.succeeded()) {
                outboxMapper.recordPublishFailure(
                        event.id(),
                        event.publishAttempts(),
                        maximumAttempts,
                        retryDelaySeconds(event.publishAttempts() + 1),
                        attempt.errorCode());
                break;
            }
            if (outboxMapper.markPublished(event.id(), event.publishAttempts()) == 1) {
                published++;
            }
        }
        return published;
    }

    private PublishAttempt publishConfirmed(OutboxEventRow event) {
        CorrelationData correlation = new CorrelationData(event.id());
        try {
            rabbitTemplate.convertAndSend(
                    RabbitTopology.EXCHANGE,
                    routingKey(event),
                    event.payload(),
                    message -> {
                        message.getMessageProperties().setContentType("application/json");
                        message.getMessageProperties().setContentEncoding("UTF-8");
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setMessageId(event.id());
                        return message;
                    },
                    correlation);

            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                LOGGER.warn(
                        "RabbitMQ negatively acknowledged Outbox event {}; reason={}",
                        event.id(),
                        confirm.reason());
                return PublishAttempt.failed("BROKER_NACK");
            }
            if (correlation.getReturned() != null) {
                LOGGER.warn("RabbitMQ returned unroutable Outbox event {}", event.id());
                return PublishAttempt.failed("UNROUTABLE");
            }
            return PublishAttempt.success();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while publishing Outbox event {}", event.id());
            return PublishAttempt.failed("INTERRUPTED");
        } catch (Exception failure) {
            LOGGER.warn(
                    "Failed to publish Outbox event {}; failureType={}",
                    event.id(),
                    failure.getClass().getSimpleName());
            return PublishAttempt.failed("PUBLISH_EXCEPTION");
        }
    }

    private long retryDelaySeconds(int failedAttemptNo) {
        long delay = baseDelaySeconds;
        for (int attempt = 1; attempt < failedAttemptNo && delay < maximumDelaySeconds; attempt++) {
            delay = Math.min(
                    maximumDelaySeconds,
                    delay > maximumDelaySeconds / 2 ? maximumDelaySeconds : delay * 2);
        }
        return delay;
    }

    private String routingKey(OutboxEventRow event) {
        return switch (event.eventType()) {
            case "JUDGE_TASK_QUEUED" -> RabbitTopology.ROUTING_KEY;
            case "JUDGE_TASK_DEAD_LETTERED" -> RabbitTopology.DEAD_LETTER_ROUTING_KEY;
            default -> throw new IllegalArgumentException(
                    "Unsupported Outbox event type: " + event.eventType());
        };
    }

    private record PublishAttempt(boolean succeeded, String errorCode) {

        private static PublishAttempt success() {
            return new PublishAttempt(true, null);
        }

        private static PublishAttempt failed(String errorCode) {
            return new PublishAttempt(false, errorCode);
        }
    }
}
