/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;
import java.util.*;
import com.forgeoj.api.cache.CacheMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class SearchRebuilder {
    private final SearchMapper search;private final SearchRebuildMapper jobs;private final CacheMapper epochs;private final ElasticSearchClient es;
    private final TransactionTemplate transaction,snapshot,independent;private final boolean background;
    private final com.forgeoj.api.admin.SearchRebuildAudit audit;
    private final SearchDeliveryMapper deliveries;
    public SearchRebuilder(SearchMapper search,SearchRebuildMapper jobs,CacheMapper epochs,ElasticSearchClient es,PlatformTransactionManager manager,@Value("${forgeoj.search.background-enabled:true}")boolean background,com.forgeoj.api.admin.SearchRebuildAudit audit,SearchDeliveryMapper deliveries){
        this.search=search;this.jobs=jobs;this.epochs=epochs;this.es=es;this.background=background;
        this.audit=audit;
        this.deliveries=deliveries;
        transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        independent=new TransactionTemplate(manager);independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        snapshot=new TransactionTemplate(manager);snapshot.setReadOnly(true);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Scheduled(fixedDelayString="${forgeoj.search.rebuild-delay-ms:2000}") void scheduled(){if(es.enabled()&&background)try{run();}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Search rebuild unavailable; code=REBUILD_UNAVAILABLE");}}
    record Claim(SearchRebuildMapper.Job job,boolean finalRecovery) {}
    public void run(){
        String active=search.control().activeJob();if(active==null)return;
        var claim=transaction.execute(s->{search.lockControl();var j=jobs.lock(active).orElseThrow();if(!j.expired())return null;
            String token=UUID.randomUUID().toString();
            if(j.attempts()>=5){
                if(j.status().equals("READY")&&jobs.finalRecoveryLease(active,token)==1)return new Claim(jobs.job(active).orElseThrow(),true);
                if(jobs.exhausted(active)==1){jobs.clear(active);audit.terminal(j,"FAILED");}return null;
            }
            if(jobs.claim(active,token)!=1)return null;return new Claim(jobs.job(active).orElseThrow(),false);});
        if(claim==null)return;var job=claim.job();
        try{
            if(claim.finalRecovery()){
                // The last indexing attempt may have switched ES before its DB commit crashed.
                // Verify the durable READY intent once; never restart indexing or reset attempt facts.
                var actual=es.alias();if(!actual.index().equals(job.targetIndex())||!actual.uuid().equals(job.targetUuid()))throw ElasticSearchClient.unavailable();
                transaction.executeWithoutResult(s->{long current=epochs.lockPublic();search.lockControl();if(current!=job.targetEpoch()||jobs.succeeded(job.id(),job.leaseToken())!=1||jobs.switched(job.id(),job.targetIndex(),job.targetUuid(),current)!=1)throw ElasticSearchClient.unavailable();deliveries.recoverDead(job.id());audit.terminal(job,"SUCCEEDED");});return;
            }
            if(!es.exists(job.targetIndex()))es.create(job.targetIndex(),job.id());
            es.verifyOwned(job.targetIndex(),job.id());String uuid=es.uuid(job.targetIndex());
            long epoch=snapshot.execute(s->{long e=epochs.publicRevision(),after=0;
                while(true){var batch=search.scan(after);if(batch.isEmpty())break;
                    for(var p:batch){if(independent.execute(t->jobs.renew(job.id(),job.leaseToken()))!=1)throw ElasticSearchClient.unavailable();es.index(job.targetIndex(),p,search.tags(p.problemId()));after=p.problemId();}
                }
                return e;
            });
            es.refresh(job.targetIndex());
            transaction.executeWithoutResult(s->{long current=epochs.lockPublic();search.lockControl();if(current!=epoch||jobs.ready(job.id(),job.leaseToken(),uuid,epoch)!=1)throw ElasticSearchClient.unavailable();});
            // Durable READY intent precedes the cross-system switch. Recovery uses this same generation.
            var alias=es.optionalAlias();if(alias.isEmpty()||!alias.get().index().equals(job.targetIndex()))es.switchAlias(alias.map(ElasticSearchClient.Alias::index).orElse(null),job.targetIndex());
            var actual=es.alias();if(!actual.index().equals(job.targetIndex())||!actual.uuid().equals(uuid))throw ElasticSearchClient.unavailable();
            transaction.executeWithoutResult(s->{long current=epochs.lockPublic();search.lockControl();if(current!=epoch||jobs.succeeded(job.id(),job.leaseToken())!=1||jobs.switched(job.id(),job.targetIndex(),uuid,epoch)!=1)throw ElasticSearchClient.unavailable();deliveries.recoverDead(job.id());audit.terminal(job,"SUCCEEDED");});
        }catch(RuntimeException failure){transaction.executeWithoutResult(s->{search.lockControl();if(jobs.failed(job.id(),job.leaseToken())==1&&jobs.job(job.id()).orElseThrow().status().equals("FAILED")){jobs.clear(job.id());audit.terminal(job,"FAILED");}});}
    }
}
