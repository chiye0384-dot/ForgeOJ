/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import com.rabbitmq.client.Channel;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name="forgeoj.search.enabled",havingValue="true")
public class SearchMessageListener {
    private final SearchSynchronizer sync;private final SearchDeliveryMapper mapper;private final ObjectMapper json;
    public SearchMessageListener(SearchSynchronizer sync,SearchDeliveryMapper mapper,ObjectMapper json){this.sync=sync;this.mapper=mapper;this.json=json;}
    @RabbitListener(queues=SearchRabbitConfig.QUEUE,containerFactory="searchListenerFactory",ackMode="MANUAL",concurrency="1",autoStartup="${forgeoj.search.background-enabled:true}")
    public void handle(Message message,Channel channel)throws Exception {
        long tag=message.getMessageProperties().getDeliveryTag();String id;
        try{
            if(message.getBody().length>1024)throw new IllegalArgumentException();
            var body=json.readTree(message.getBody());if(!body.isObject()||!body.propertyNames().equals(Set.of("eventId","problemId","dataVersion","type")))throw new IllegalArgumentException();
            id=body.path("eventId").asText();if(!UUID.fromString(id).toString().equals(id)||!validInteger(body.path("problemId"))||!validInteger(body.path("dataVersion"))||!body.path("type").asText().equals("PUBLIC_SEARCH_CHANGED"))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){channel.basicReject(tag,false);return;}
        while(channel.isOpen()&&!Thread.currentThread().isInterrupted()){
            try{
                var event=mapper.event(id);var body=json.readTree(message.getBody());
                if(event.isEmpty()||event.get().problemId()!=body.path("problemId").asLong()||event.get().dataVersion()!=body.path("dataVersion").asLong()){channel.basicReject(tag,false);return;}
                sync.receive(id);break;
            }catch(org.springframework.dao.DataAccessException unavailable){TimeUnit.SECONDS.sleep(1);}
        }
        // Prefetch=1 bounds unsettled messages. Durable finite attempts survive connection/process loss.
        while(channel.isOpen()&&!Thread.currentThread().isInterrupted()){
            try{sync.process(id);if(sync.ackReady(id)){channel.basicAck(tag,false);return;}}catch(RuntimeException unavailable){/* No ACK before durable terminal state. */}
            TimeUnit.SECONDS.sleep(1);
        }
    }
    private boolean validInteger(tools.jackson.databind.JsonNode value){return value.isIntegralNumber()&&value.canConvertToLong()&&value.asLong()>=1&&value.asLong()<=9007199254740991L;}
}
