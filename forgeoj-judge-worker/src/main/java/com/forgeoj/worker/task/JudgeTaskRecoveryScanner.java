package com.forgeoj.worker.task;

import com.forgeoj.worker.messaging.JudgeTaskMessage;
import com.forgeoj.worker.messaging.RabbitTopology;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "forgeoj.worker.recovery.enabled", havingValue = "true")
public class JudgeTaskRecoveryScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(JudgeTaskRecoveryScanner.class);

    private final JudgeTaskRecoveryMapper mapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    JudgeTaskRecoveryScanner(
            JudgeTaskRecoveryMapper mapper,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${forgeoj.worker.recovery.batch-size:20}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Recovery scanner batch size must be positive");
        }
        this.mapper = mapper;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${forgeoj.worker.recovery.fixed-delay-ms:5000}")
    void scheduledRecover() {
        recoverDueTasks();
    }

    public int recoverDueTasks() {
        int published = 0;
        for (JudgeTaskRecoveryCandidate candidate : mapper.findDue(batchSize)) {
            try {
                rabbitTemplate.send(
                        RabbitTopology.EXCHANGE,
                        RabbitTopology.ROUTING_KEY,
                        recoveryMessage(candidate));
                published++;
            } catch (RuntimeException publishFailure) {
                LOGGER.warn(
                        "Could not republish recoverable judge task {}; failureType={}",
                        candidate.taskId(),
                        publishFailure.getClass().getSimpleName());
                break;
            }
        }
        return published;
    }

    private Message recoveryMessage(JudgeTaskRecoveryCandidate candidate) {
        JudgeTaskMessage payload =
                new JudgeTaskMessage(
                        candidate.taskId(),
                        candidate.submissionId(),
                        candidate.taskType(),
                        candidate.contractVersion());
        try {
            return MessageBuilder.withBody(objectMapper.writeValueAsBytes(payload))
                    .setContentType("application/json")
                    .setContentEncoding("UTF-8")
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .build();
        } catch (JacksonException serializationFailure) {
            throw new IllegalStateException(
                    "Could not serialize judge task recovery message", serializationFailure);
        }
    }
}
