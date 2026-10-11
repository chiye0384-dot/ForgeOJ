/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.observability;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/** Process counters have no submission, task, user, outcome text or credential labels. */
public final class MonitoringMetrics {
    private static final List<String> EVENTS=List.of("delivery.rejected","delivery.received","delivery.claim_failed","delivery.claim_result",
        "delivery.execution_failed","delivery.ack_failed","delivery.ack_sent","attempt.finished","attempt.retry_scheduled","attempt.dead_lettered",
        "attempt.heartbeat_failed","attempt.claimed","task.quota_deferred","task.exhausted","task.lease_recovered","recovery.sent","recovery.send_failed",
        "sandbox.cleanup_failed","sandbox.execution_cleanup_failed");
    public static final MonitoringMetrics PROCESS=new MonitoringMetrics();
    private final LongAdder[] counts=adders(EVENTS.size());
    private final LongAdder executions=new LongAdder(),nanos=new LongAdder();
    private static LongAdder[] adders(int n){var a=new LongAdder[n];java.util.Arrays.setAll(a,i->new LongAdder());return a;}
    public void event(String event){if(event==null)return;int index=EVENTS.indexOf(event);if(index>=0)counts[index].increment();}
    public void execution(long durationNanos){nanos.add(Math.max(0,durationNanos));executions.increment();}
    public String render(){
        var out=new StringBuilder("# HELP forgeoj_worker_events_total Process-local lifecycle events since start.\n# TYPE forgeoj_worker_events_total counter\n");
        for(int i=0;i<EVENTS.size();i++)out.append("forgeoj_worker_events_total{event=\"").append(EVENTS.get(i).replace('.','_')).append("\"} ").append(counts[i].sum()).append('\n');
        out.append("# HELP forgeoj_worker_execution_duration_seconds Claimed formal execution duration including completion persistence, before ACK.\n# TYPE forgeoj_worker_execution_duration_seconds summary\nforgeoj_worker_execution_duration_seconds_count ").append(executions.sum()).append('\n');
        out.append("forgeoj_worker_execution_duration_seconds_sum ").append(nanos.sum()/1_000_000_000.0).append('\n');
        return out.toString();
    }
}
