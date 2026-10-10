/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
@Configuration
@ConditionalOnProperty(name="forgeoj.search.enabled",havingValue="true")
public class SearchRabbitConfig {
    public static final String EXCHANGE="forgeoj.search.direct",QUEUE="forgeoj.search.public",ROUTE="public.search",DEAD=QUEUE+".dlq",DEAD_ROUTE="public.search.dead";
    @Bean SimpleRabbitListenerContainerFactory searchListenerFactory(ConnectionFactory connection){var factory=new SimpleRabbitListenerContainerFactory();factory.setConnectionFactory(connection);factory.setPrefetchCount(1);factory.setConcurrentConsumers(1);factory.setMaxConcurrentConsumers(1);factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);return factory;}
    @Bean Declarables searchTopology(){return new Declarables(new DirectExchange(EXCHANGE,true,false),
        QueueBuilder.durable(QUEUE).deadLetterExchange(EXCHANGE).deadLetterRoutingKey(DEAD_ROUTE).build(),new Queue(DEAD,true),
        new Binding(QUEUE,Binding.DestinationType.QUEUE,EXCHANGE,ROUTE,null),new Binding(DEAD,Binding.DestinationType.QUEUE,EXCHANGE,DEAD_ROUTE,null));}
}
