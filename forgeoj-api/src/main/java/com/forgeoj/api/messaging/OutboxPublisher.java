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

    public OutboxPublisher(
            OutboxMapper outboxMapper,
            RabbitTemplate rabbitTemplate,
            @Value("${forgeoj.outbox.publisher.enabled:false}")
                    boolean scheduledPublishingEnabled,
            @Value("${forgeoj.outbox.publisher.batch-size:20}") int batchSize,
            @Value("${forgeoj.outbox.publisher.confirm-timeout-ms:5000}")
                    long confirmTimeoutMs) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.scheduledPublishingEnabled = scheduledPublishingEnabled;
        this.batchSize = batchSize;
        this.confirmTimeoutMs = confirmTimeoutMs;
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
            if (!publishConfirmed(event)) {
                break;
            }
            if (outboxMapper.markPublished(event.id()) == 1) {
                published++;
            }
        }
        return published;
    }

    private boolean publishConfirmed(OutboxEventRow event) {
        CorrelationData correlation = new CorrelationData(event.id());
        try {
            rabbitTemplate.convertAndSend(
                    RabbitTopology.EXCHANGE,
                    RabbitTopology.ROUTING_KEY,
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
                return false;
            }
            if (correlation.getReturned() != null) {
                LOGGER.warn("RabbitMQ returned unroutable Outbox event {}", event.id());
                return false;
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while publishing Outbox event {}", event.id());
            return false;
        } catch (Exception failure) {
            LOGGER.warn(
                    "Failed to publish Outbox event {}; failureType={}",
                    event.id(),
                    failure.getClass().getSimpleName());
            return false;
        }
    }
}
