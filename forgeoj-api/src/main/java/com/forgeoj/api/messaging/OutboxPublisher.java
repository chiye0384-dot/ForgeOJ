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
                int updated = outboxMapper.recordPublishFailure(
                        event.id(),
                        event.publishAttempts(),
                        maximumAttempts,
                        retryDelaySeconds(event.publishAttempts() + 1),
                        attempt.errorCode());
                if (updated == 1) logEvent(event, "outbox.publish_failed", attempt.errorCode());
                break;
            }
            if (outboxMapper.markPublished(event.id(), event.publishAttempts()) == 1) {
                published++;
                logEvent(event, "outbox.published", null);
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
                return PublishAttempt.failed("BROKER_NACK");
            }
            if (correlation.getReturned() != null) {
                return PublishAttempt.failed("UNROUTABLE");
            }
            return PublishAttempt.success();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return PublishAttempt.failed("INTERRUPTED");
        } catch (Exception failure) {
            return PublishAttempt.failed("PUBLISH_EXCEPTION");
        }
    }

    private void logEvent(OutboxEventRow event, String name, String errorCode) {
        try {
            var log = (errorCode == null ? LOGGER.atInfo() : LOGGER.atWarn())
                    .addKeyValue("event", name)
                    .addKeyValue("outboxEventId", event.id())
                    .addKeyValue(event.eventType().startsWith("CONTENT_VALIDATION") ? "contentJobId" : "judgeTaskId", event.judgeTaskId())
                    .addKeyValue("submissionId", event.submissionId())
                    .addKeyValue("sequenceNo", event.sequenceNo())
                    .addKeyValue("publishAttemptNo", event.publishAttempts() + 1);
            if (errorCode != null) log.addKeyValue("failureCode", errorCode);
            log.log("Outbox publication outcome persisted");
        } catch (RuntimeException loggingFailure) {
            // Observability must not change transport or persistence semantics.
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
            case "JUDGE_TASK_QUEUED" ->
                    event.sequenceNo() == 0
                            ? RabbitTopology.ROUTING_KEY
                            : RabbitTopology.RETRY_ROUTING_KEY;
            case "JUDGE_TASK_DEAD_LETTERED" -> RabbitTopology.DEAD_LETTER_ROUTING_KEY;
            case "CONTENT_VALIDATION_QUEUED" -> "content.validation.v1";
            case "CONTENT_VALIDATION_DEAD_LETTERED" -> "content.validation.dead.v1";
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
