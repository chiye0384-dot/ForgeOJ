/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.forgeoj.api.cache.CacheMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class SearchSynchronizer {
    private final SearchDeliveryMapper deliveries;private final SearchMapper mapper;private final CacheMapper epochs;
    private final ElasticSearchClient es;private final RabbitTemplate rabbit;private final ObjectMapper json;
    private final TransactionTemplate transaction,snapshot;private final boolean background;
    public SearchSynchronizer(SearchDeliveryMapper deliveries,SearchMapper mapper,CacheMapper epochs,ElasticSearchClient es,RabbitTemplate rabbit,ObjectMapper json,PlatformTransactionManager manager,@Value("${forgeoj.search.background-enabled:true}")boolean background){
        this.deliveries=deliveries;this.mapper=mapper;this.epochs=epochs;this.es=es;this.rabbit=rabbit;this.json=json;this.background=background;
        transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        snapshot=new TransactionTemplate(manager);snapshot.setReadOnly(true);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Scheduled(fixedDelayString="${forgeoj.search.sync-delay-ms:1000}") void scheduled(){if(es.enabled()&&background)try{publish();for(String id:deliveries.due())process(id);watermark();}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Search synchronization unavailable; code=SEARCH_UNAVAILABLE");}}
    public Map<String,Object> payload(SearchMapper.Event event){return Map.of("eventId",event.id(),"problemId",event.problemId(),"dataVersion",event.dataVersion(),"type","PUBLIC_SEARCH_CHANGED");}
    public void publish(){
        for(var event:deliveries.pending()){
            if(send(event.id(),SearchRabbitConfig.ROUTE,payload(event)))deliveries.published(event.id(),event.publishAttempts());
            else deliveries.publishFailed(event.id(),event.publishAttempts(),1<<event.publishAttempts());
        }
        for(var dead:deliveries.pendingDead()){
            var event=deliveries.event(dead.eventId()).orElseThrow();
            if(send(event.id(),SearchRabbitConfig.DEAD_ROUTE,payload(event)))deliveries.deadConfirmed(event.id(),dead.attempts());
            else deliveries.deadFailed(event.id(),dead.attempts(),1<<dead.attempts());
        }
        for(var recovery:deliveries.pendingRecovery()){
            var event=deliveries.event(recovery.eventId()).orElseThrow();
            if(send(event.id(),SearchRabbitConfig.DEAD_ROUTE,payload(event)))deliveries.recovered(event.id(),recovery.rebuildId(),recovery.attempts());
            else deliveries.recoveryFailed(event.id(),recovery.rebuildId(),recovery.attempts(),1<<recovery.attempts());
        }
    }
    private boolean send(String id,String route,Object body){
        var correlation=new CorrelationData(id);
        try{
            rabbit.convertAndSend(SearchRabbitConfig.EXCHANGE,route,json.writeValueAsString(body),m->{m.getMessageProperties().setContentType("application/json");m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);m.getMessageProperties().setMessageId(id);return m;},correlation);
            return correlation.getFuture().get(1,TimeUnit.SECONDS).ack()&&correlation.getReturned()==null;
        }catch(InterruptedException failure){Thread.currentThread().interrupt();return false;}catch(Exception failure){return false;}
    }
    public void receive(String id){transaction.executeWithoutResult(s->{deliveries.initialize(id);});}
    record Claim(String id,String token,int attempt,String index) {}
    public void process(String id){
        Claim claim=transaction.execute(s->{
            var control=mapper.control();if(control.indexName()==null)return null;
            var d=deliveries.lock(id);if(d==null||Set.of("SUCCEEDED","DEAD_LETTER").contains(d.status())||!d.due()||!d.expired())return null;
            if(d.attempts()>=5){if(deliveries.exhausted(id)==1)deliveries.dead(id);return null;}
            String token=UUID.randomUUID().toString();if(deliveries.claim(id,token)!=1)return null;return new Claim(id,token,d.attempts()+1,control.indexName());
        });
        if(claim==null)return;
        String error=null;
        try{
            var event=deliveries.event(id).orElseThrow();
            snapshot.executeWithoutResult(s->{var p=mapper.projection(event.problemId()).orElseThrow(ElasticSearchClient::unavailable);es.index(claim.index(),p,mapper.tags(p.problemId()));});
        }catch(RuntimeException failure){error="INDEX_UNAVAILABLE";}
        String persistedError=error;
        transaction.executeWithoutResult(s->{var control=mapper.lockControl();String failure=persistedError;
            if(failure==null&&!claim.index().equals(control.indexName()))failure="INDEX_GENERATION_CHANGED";
            String status=failure==null?"SUCCEEDED":claim.attempt()>=5?"DEAD_LETTER":"QUEUED";
            if(deliveries.finish(id,claim.token(),status,failure,1<<(claim.attempt()-1))==1&&status.equals("DEAD_LETTER"))deliveries.dead(id);});
    }
    public boolean ackReady(String id){var status=deliveries.status(id).orElse("");return status.equals("SUCCEEDED")||status.equals("DEAD_LETTER")&&deliveries.deadPublished(id);}
    public void watermark(){
        transaction.executeWithoutResult(s->{long epoch=epochs.lockPublic();var c=mapper.lockControl();
            if(c.indexName()==null||c.activeJob()!=null||deliveries.outstanding(c.readableEpoch(),epoch)!=0)return;
            var actual=es.alias();if(!actual.index().equals(c.indexName())||!actual.uuid().equals(c.indexUuid()))throw ElasticSearchClient.unavailable();
            es.refresh(c.indexName());mapper.readable(c.indexUuid(),epoch);
        });
    }
}
