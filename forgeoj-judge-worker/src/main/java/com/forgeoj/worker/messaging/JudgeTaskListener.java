package com.forgeoj.worker.messaging;

import com.forgeoj.worker.task.JudgeTaskClaimService;
import com.forgeoj.worker.task.TaskClaimResult;
import com.forgeoj.worker.task.TaskClaimOutcome;
import com.rabbitmq.client.Channel;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "forgeoj.worker.consumer.enabled", havingValue = "true")
final class JudgeTaskListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(JudgeTaskListener.class);

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
            LOGGER.warn("Rejected invalid judge task message");
            channel.basicReject(deliveryTag, false);
            return;
        }

        TaskClaimResult claim;
        try {
            claim = claimService.claim(message);
        } catch (RuntimeException databaseFailure) {
            LOGGER.warn(
                    "Could not claim judge task {}; failureType={}",
                    message.taskId(),
                    databaseFailure.getClass().getSimpleName());
            channel.basicNack(deliveryTag, false, true);
            return;
        }

        if (claim.outcome() == TaskClaimOutcome.REJECTED) {
            LOGGER.warn(
                    "Rejected judge task contract mismatch; taskId={}, submissionId={}",
                    message.taskId(),
                    message.submissionId());
            channel.basicReject(deliveryTag, false);
            return;
        }
        if (claim.outcome() == TaskClaimOutcome.EXHAUSTED) {
            LOGGER.warn("Judge task exhausted its attempt limit; taskId={}", message.taskId());
            channel.basicAck(deliveryTag, false);
            return;
        }
        if (claim.outcome() == TaskClaimOutcome.DUPLICATE) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            runner.run(claim.claimedTask());
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException executionFailure) {
            LOGGER.error(
                    "Claimed judge task execution failed; taskId={}, failureType={}",
                    message.taskId(),
                    executionFailure.getClass().getSimpleName());
            channel.basicReject(deliveryTag, false);
        }
    }
}
