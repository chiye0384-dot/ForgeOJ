/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.content;

import java.io.IOException;
import java.util.*;
import com.rabbitmq.client.Channel;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.StreamReadFeature;

@Component
@ConditionalOnProperty(name={"forgeoj.worker.consumer.enabled","forgeoj.worker.sandbox.enabled"},havingValue="true")
public class ValidationListener {
    private final ObjectMapper json;
    private final ValidationLeaseService leases;
    private final ValidationRunner runner;
    public ValidationListener(ObjectMapper json,ValidationLeaseService leases,ValidationRunner runner) {this.json=json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();this.leases=leases;this.runner=runner;}
    @RabbitListener(queues="forgeoj.content.validation.v1")
    public void consume(Message message,Channel channel) throws IOException {
        long tag=message.getMessageProperties().getDeliveryTag();String id,snapshot;
        try {
            if(message.getBody().length>1024) throw new IllegalArgumentException();
            var tree=json.readTree(message.getBody());var names=new HashSet<String>();tree.propertyNames().forEach(names::add);
            if(!tree.isObject() || !names.equals(Set.of("taskId","snapshotId","taskType","contractVersion"))
                || !tree.get("taskId").isString() || !tree.get("snapshotId").isString()
                || !tree.get("taskType").isString() || !tree.get("taskType").stringValue().equals("CONTENT_VALIDATE")
                || !tree.get("contractVersion").isIntegralNumber() || !tree.get("contractVersion").canConvertToInt()
                || tree.get("contractVersion").intValue()!=1) throw new IllegalArgumentException();
            id=tree.get("taskId").stringValue();snapshot=tree.get("snapshotId").stringValue();
            if(!UUID.fromString(id).toString().equals(id) || !UUID.fromString(snapshot).toString().equals(snapshot)) throw new IllegalArgumentException();
        } catch(RuntimeException invalid) {channel.basicReject(tag,false);log("content.delivery_rejected",null,null);return;}
        ValidationLeaseService.Claim claim;
        try {claim=leases.claim(id,snapshot);}catch(IllegalArgumentException invalid) {channel.basicReject(tag,false);log("content.delivery_rejected",id,null);return;}
        catch(RuntimeException unavailable) {channel.basicNack(tag,false,true);log("content.claim_failed",id,null);return;}
        try {
            if(claim!=null) {log("content.attempt_claimed",id,claim.attemptId());runner.run(claim);log("content.attempt_committed",id,claim.attemptId());}
        } catch(RuntimeException failure) {channel.basicNack(tag,false,true);log("content.commit_failed",id,claim==null?null:claim.attemptId());return;}
        channel.basicAck(tag,false);log("content.ack_sent",id,claim==null?null:claim.attemptId());
    }
    private static void log(String event,String job,String attempt) {try{LoggerFactory.getLogger(ValidationListener.class).atInfo().addKeyValue("event",event).addKeyValue("contentJobId",job).addKeyValue("contentAttemptId",attempt).log("Content validation delivery outcome");}catch(RuntimeException ignored){}}
}
