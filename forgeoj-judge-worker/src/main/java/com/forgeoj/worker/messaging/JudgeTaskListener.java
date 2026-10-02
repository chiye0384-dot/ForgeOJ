package com.forgeoj.worker.messaging;

import com.forgeoj.worker.observability.JudgingEvents;
import com.forgeoj.worker.task.ClaimedJudgeTask;
import com.forgeoj.worker.task.JudgeTaskClaimService;
import com.forgeoj.worker.task.TaskClaimOutcome;
import com.forgeoj.worker.task.TaskClaimResult;
import com.rabbitmq.client.Channel;

import java.io.IOException;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "forgeoj.worker.consumer.enabled", havingValue = "true")
final class JudgeTaskListener {

    private final JudgeTaskMessageParser parser;
    private final JudgeTaskClaimService claimService;
    private final JudgeTaskRunner runner;

    JudgeTaskListener(
            JudgeTaskMessageParser parser,
            JudgeTaskClaimService claimService,
            JudgeTaskRunner runner) {
        this.parser = parser;
        this.claimService = claimService;
        this.runner = runner;
    }

    @RabbitListener(queues = RabbitTopology.QUEUE)
    void consumeSubmission(Message inbound, Channel channel) throws IOException {
        consume(inbound, channel);
    }

    @RabbitListener(queues = RabbitTopology.RETRY_QUEUE)
    void consumeRetry(Message inbound, Channel channel) throws IOException {
        consume(inbound, channel);
    }

    private void consume(Message inbound, Channel channel) throws IOException {
        long deliveryTag = inbound.getMessageProperties().getDeliveryTag();
        JudgeTaskMessage message;
        try {
            message = parser.parse(inbound.getBody());
        } catch (InvalidJudgeTaskMessageException invalid) {
            JudgingEvents.record("delivery.rejected", null, null, null, "INVALID_MESSAGE");
            channel.basicReject(deliveryTag, false);
            return;
        }
        JudgingEvents.record("delivery.received", message, null, null, null);
        TaskClaimResult claim;
        try {
            claim = claimService.claim(message);
        } catch (RuntimeException databaseFailure) {
            JudgingEvents.record("delivery.claim_failed", message, null, null, "CLAIM_FAILURE");
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        JudgingEvents.record("delivery.claim_result", message, claim.claimedTask(),
                claim.outcome().name(), null);
        if (claim.outcome() == TaskClaimOutcome.REJECTED) {
            channel.basicReject(deliveryTag, false);
            return;
        }
        if (claim.outcome() != TaskClaimOutcome.CLAIMED) {
            acknowledge(channel, deliveryTag, message, null);
            return;
        }
        try {
            runner.run(claim.claimedTask());
        } catch (RuntimeException executionFailure) {
            JudgingEvents.record("delivery.execution_failed", message, claim.claimedTask(),
                    null, "EXECUTION_FAILURE");
            channel.basicReject(deliveryTag, false);
            return;
        }
        try {
            acknowledge(channel, deliveryTag, message, claim.claimedTask());
        } catch (RuntimeException ackFailure) {
            // Preserve the existing claimed-delivery rejection path for runtime ACK failures.
            channel.basicReject(deliveryTag, false);
        }
    }

    private void acknowledge(Channel channel, long tag, JudgeTaskMessage message,
            ClaimedJudgeTask attempt) throws IOException {
        try {
            channel.basicAck(tag, false);
        } catch (IOException | RuntimeException ackFailure) {
            JudgingEvents.record("delivery.ack_failed", message, attempt, null, "ACK_FAILURE");
            throw ackFailure;
        }
        // Client accepted the ACK write; this does not prove broker receipt.
        JudgingEvents.record("delivery.ack_sent", message, attempt, null, null);
    }
}
