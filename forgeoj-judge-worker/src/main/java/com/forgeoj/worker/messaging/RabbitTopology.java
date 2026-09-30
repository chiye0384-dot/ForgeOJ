package com.forgeoj.worker.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopology {

    public static final String EXCHANGE = "forgeoj.judge";
    public static final String QUEUE = "forgeoj.judge.submission.v1";
    public static final String ROUTING_KEY = "judge.submission.v1";
    public static final String DEAD_LETTER_QUEUE = "forgeoj.judge.submission.dead.v1";
    public static final String DEAD_LETTER_ROUTING_KEY = "judge.submission.dead.v1";

    @Bean
    DirectExchange judgeExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue judgeSubmissionQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    Queue judgeSubmissionDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding judgeSubmissionBinding(DirectExchange judgeExchange, Queue judgeSubmissionQueue) {
        return BindingBuilder.bind(judgeSubmissionQueue)
                .to(judgeExchange)
                .with(ROUTING_KEY);
    }

    @Bean
    Binding judgeSubmissionDeadLetterBinding(
            DirectExchange judgeExchange, Queue judgeSubmissionDeadLetterQueue) {
        return BindingBuilder.bind(judgeSubmissionDeadLetterQueue)
                .to(judgeExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }
}
