/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.selftest;

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
public class SelfTestListener {
    private final ObjectMapper json;
    private final SelfTestLeaseService leases;
    private final SelfTestRunner runner;
    public SelfTestListener(ObjectMapper json,SelfTestLeaseService leases,SelfTestRunner runner) {this.json=json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();this.leases=leases;this.runner=runner;}
    @RabbitListener(queues="forgeoj.judge.self-test.v1")
    public void consume(Message message,Channel channel) throws IOException {
        long tag=message.getMessageProperties().getDeliveryTag();String id,snapshot;
        try {
            if(message.getBody().length>1024) throw new IllegalArgumentException();
            var tree=json.readTree(message.getBody());var names=new HashSet<String>();tree.propertyNames().forEach(names::add);
            if(!tree.isObject() || !names.equals(Set.of("taskId","snapshotId","taskType","contractVersion"))
                || !tree.get("taskId").isString() || !tree.get("snapshotId").isString()
                || !tree.get("taskType").isString() || !tree.get("taskType").stringValue().equals("SELF_TEST")
                || !tree.get("contractVersion").isIntegralNumber() || !tree.get("contractVersion").canConvertToInt()
                || tree.get("contractVersion").intValue()!=1) throw new IllegalArgumentException();
            id=tree.get("taskId").stringValue();snapshot=tree.get("snapshotId").stringValue();
            if(!UUID.fromString(id).toString().equals(id) || !UUID.fromString(snapshot).toString().equals(snapshot)) throw new IllegalArgumentException();
        } catch(RuntimeException invalid) {channel.basicReject(tag,false);log("selftest.delivery_rejected",null,null);return;}
        SelfTestLeaseService.Claim claim;
        try {claim=leases.claim(id,snapshot);}catch(IllegalArgumentException invalid) {channel.basicReject(tag,false);log("selftest.delivery_rejected",id,null);return;}
        catch(RuntimeException unavailable) {channel.basicNack(tag,false,true);log("selftest.claim_failed",id,null);return;}
        try {
            if(claim!=null) {log("selftest.attempt_claimed",id,claim.attemptId());runner.run(claim);log("selftest.attempt_committed",id,claim.attemptId());}
        } catch(RuntimeException failure) {channel.basicNack(tag,false,true);log("selftest.commit_failed",id,claim==null?null:claim.attemptId());return;}
        channel.basicAck(tag,false);log("selftest.ack_sent",id,claim==null?null:claim.attemptId());
    }
    private static void log(String event,String job,String attempt) {try{LoggerFactory.getLogger(SelfTestListener.class).atInfo().addKeyValue("event",event).addKeyValue("selfTestRunId",job).addKeyValue("selfTestAttemptId",attempt).log("Content self-test delivery outcome");}catch(RuntimeException ignored){}}
}
