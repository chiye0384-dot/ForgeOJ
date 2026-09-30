package com.forgeoj.worker.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.forgeoj.worker.messaging.RabbitTopology;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import tools.jackson.databind.json.JsonMapper;

class JudgeTaskRecoveryScannerTests {

    private static final JudgeTaskRecoveryCandidate CANDIDATE =
            new JudgeTaskRecoveryCandidate(
                    "b844c173-9436-4d35-a45c-a6f5041f7d20",
                    "a9987de8-880d-42af-b463-06ae4f7b9717",
                    "JUDGE_SUBMISSION",
                    1);

    @Test
    void republishesOnlyTheFourFieldPersistentTaskContract() {
        JudgeTaskRecoveryMapper mapper = mock(JudgeTaskRecoveryMapper.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(mapper.findDue(20)).thenReturn(List.of(CANDIDATE));
        JudgeTaskRecoveryScanner scanner =
                new JudgeTaskRecoveryScanner(
                        mapper, rabbitTemplate, JsonMapper.builder().build(), 20);

        assertThat(scanner.recoverDueTasks()).isEqualTo(1);

        ArgumentCaptor<Message> outbound = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate)
                .send(
                        org.mockito.ArgumentMatchers.eq(RabbitTopology.EXCHANGE),
                        org.mockito.ArgumentMatchers.eq(RabbitTopology.ROUTING_KEY),
                        outbound.capture());
        assertThat(outbound.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(outbound.getValue().getMessageProperties().getContentType())
                .isEqualTo("application/json");
        assertThat(new String(outbound.getValue().getBody(), StandardCharsets.UTF_8))
                .isEqualTo(
                        """
                        {"taskId":"b844c173-9436-4d35-a45c-a6f5041f7d20","submissionId":"a9987de8-880d-42af-b463-06ae4f7b9717","taskType":"JUDGE_SUBMISSION","contractVersion":1}
                        """
                                .strip());
    }

    @Test
    void leavesDueTaskRecoverableWhenRabbitPublishFails() {
        JudgeTaskRecoveryMapper mapper = mock(JudgeTaskRecoveryMapper.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(mapper.findDue(20)).thenReturn(List.of(CANDIDATE));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(rabbitTemplate)
                .send(any(String.class), any(String.class), any(Message.class));
        JudgeTaskRecoveryScanner scanner =
                new JudgeTaskRecoveryScanner(
                        mapper, rabbitTemplate, JsonMapper.builder().build(), 20);

        assertThat(scanner.recoverDueTasks()).isZero();
        verify(mapper).findDue(20);
    }
}
