/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.observability;
import java.time.Clock;
import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Component @ConditionalOnProperty(name="forgeoj.monitoring.enabled",havingValue="true")
public final class MonitoringSnapshot {
    private static final List<String> KINDS=List.of("FORMAL","VALIDATE","OUTPUT_PREVIEW","SELF_TEST"),STATES=List.of("QUEUED","RUNNING","RETRYING","WAITING_RETRY","FINISHED","CANCELLED","SYSTEM_ERROR","DEAD_LETTER"),VERDICTS=List.of("AC","WA","CE","RE","TLE","OLE","MLE","SECURITY_VIOLATION","SYSTEM_ERROR");
    private final MonitoringMapper mapper;private final TransactionTemplate transaction;private final Clock clock;private final LongSupplier monotonic;
    private record Sample(String text,long atNanos,long atSeconds,boolean hasGood,boolean success) {}
    private volatile Sample sample=new Sample("",0,0,false,false);
    @Autowired public MonitoringSnapshot(MonitoringMapper mapper,PlatformTransactionManager manager){this(mapper,manager,Clock.systemUTC(),System::nanoTime);}
    MonitoringSnapshot(MonitoringMapper mapper,PlatformTransactionManager manager,Clock clock,LongSupplier monotonic){this.mapper=mapper;this.clock=clock;this.monotonic=monotonic;transaction=new TransactionTemplate(manager);transaction.setReadOnly(true);transaction.setTimeout(2);transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    public void refresh(){
        try{String text=transaction.execute(s->collect());sample=new Sample(text,monotonic.getAsLong(),clock.instant().getEpochSecond(),true,true);}
        catch(RuntimeException unavailable){var old=sample;sample=new Sample(old.text(),old.atNanos(),old.atSeconds(),old.hasGood(),false);}
    }
    public boolean fresh(){return fresh(sample);}
    private boolean fresh(Sample s){return s.success()&&s.hasGood()&&monotonic.getAsLong()-s.atNanos()<45_000_000_000L;}
    public String render(){var s=sample;return "# HELP forgeoj_monitoring_collection_success Whether the most recent SQL collection succeeded.\n# TYPE forgeoj_monitoring_collection_success gauge\nforgeoj_monitoring_collection_success "+(s.success()?1:0)+"\n# HELP forgeoj_monitoring_last_success_seconds Unix time of the last successful SQL snapshot.\n# TYPE forgeoj_monitoring_last_success_seconds gauge\nforgeoj_monitoring_last_success_seconds "+s.atSeconds()+"\n# HELP forgeoj_monitoring_snapshot_fresh Whether a successful snapshot is less than 45 seconds old.\n# TYPE forgeoj_monitoring_snapshot_fresh gauge\nforgeoj_monitoring_snapshot_fresh "+(fresh(s)?1:0)+"\n"+s.text();}
    private String collect(){
        var out=new StringBuilder();var values=new HashMap<String,Long>();
        for(var row:mapper.tasks()){require(KINDS,row.kind());require(STATES,row.state());values.put(row.kind()+":"+row.state(),row.value());}
        out.append("# HELP forgeoj_tasks Current retained tasks by fixed kind and state.\n# TYPE forgeoj_tasks gauge\n");for(String kind:KINDS)for(String state:STATES)out.append("forgeoj_tasks{kind=\"").append(kind).append("\",state=\"").append(state).append("\"} ").append(values.getOrDefault(kind+":"+state,0L)).append('\n');
        values.clear();for(var row:mapper.verdicts()){require(VERDICTS,row.state());values.put(row.state(),row.value());}
        out.append("# HELP forgeoj_formal_results Current retained formal results, not fabricated by monitoring.\n# TYPE forgeoj_formal_results gauge\n");for(String verdict:VERDICTS)out.append("forgeoj_formal_results{verdict=\"").append(verdict).append("\"} ").append(values.getOrDefault(verdict,0L)).append('\n');
        var times=new HashMap<String,MonitoringMapper.Times>();for(var row:mapper.times()){require(KINDS,row.kind());times.put(row.kind(),row);}
        for(String name:List.of("oldest_queue_seconds","completed_duration_seconds","completed_tasks","attempt_retries")){
            out.append("# HELP forgeoj_").append(name).append(" Current retained SQL aggregate; seconds values use database timestamps.\n# TYPE forgeoj_").append(name).append(" gauge\n");
            for(String kind:KINDS){var t=times.getOrDefault(kind,new MonitoringMapper.Times(kind,0,0,0,0));double value=switch(name){case "oldest_queue_seconds"->t.oldest();case "completed_duration_seconds"->t.seconds();case "completed_tasks"->t.completed();case "attempt_retries"->t.retries();default->throw new IllegalStateException("Unsupported aggregate");};gauge(out,name,kind,value);}
        }
        var backlog=Objects.requireNonNull(mapper.backlog());
        scalar(out,"outbox_pending",backlog.outboxPending());scalar(out,"outbox_failed",backlog.outboxFailed());scalar(out,"invalidation_pending",backlog.invalidationPending());scalar(out,"invalidation_failed",backlog.invalidationFailed());scalar(out,"search_pending",backlog.searchPending());scalar(out,"search_dead",backlog.searchDead());scalar(out,"search_epoch_gap",backlog.epochGap());
        values.clear();var states=List.of("QUEUED","RUNNING","READY","SUCCEEDED","FAILED");for(var row:mapper.rebuilds()){require(states,row.state());values.put(row.state(),row.value());}
        out.append("# HELP forgeoj_search_rebuilds Current retained managed rebuild jobs by fixed state.\n# TYPE forgeoj_search_rebuilds gauge\n");for(String state:states)out.append("forgeoj_search_rebuilds{state=\"").append(state).append("\"} ").append(values.getOrDefault(state,0L)).append('\n');
        return out.toString();
    }
    private static void require(List<String> allowed,String value){if(value==null||!allowed.contains(value))throw new IllegalStateException("Unsupported monitoring dimension");}
    private static void gauge(StringBuilder out,String name,String kind,double value){if(!Double.isFinite(value)||value<0)throw new IllegalStateException("Invalid aggregate");out.append("forgeoj_").append(name).append("{kind=\"").append(kind).append("\"} ").append(value).append('\n');}
    private static void scalar(StringBuilder out,String name,long value){if(value<0)throw new IllegalStateException("Invalid aggregate");out.append("# HELP forgeoj_").append(name).append(" Current SQL backlog or public epoch difference.\n# TYPE forgeoj_").append(name).append(" gauge\nforgeoj_").append(name).append(' ').append(value).append('\n');}
}
