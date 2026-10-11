/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.observability;

import java.util.concurrent.atomic.LongAdder;

/** Fixed cardinality process telemetry; never an authorization or acceptance fact. */
public final class MonitoringMetrics {
    public enum Event {CACHE_HIT,CACHE_MISS,CACHE_BYPASS,REDIS_SUCCESS,REDIS_UNAVAILABLE,
        REDIS_DISABLED,REDIS_CIRCUIT_OPEN,REDIS_BUSY,SEARCH_FULL_TEXT,SEARCH_TITLE_FALLBACK}
    private static final String[] ROUTES={"auth","admin","library","submission","selftest","content","classroom","other"};
    private static final String[] STATUSES={"1xx","2xx","3xx","4xx","5xx","other"};
    private static final long[] LIMITS={5_000_000L,10_000_000L,25_000_000L,50_000_000L,100_000_000L,
        250_000_000L,500_000_000L,1_000_000_000L,2_500_000_000L,5_000_000_000L};
    public static final MonitoringMetrics PROCESS=new MonitoringMetrics();
    private final LongAdder[] events=adders(Event.values().length),requests=adders(ROUTES.length*STATUSES.length),nanos=adders(ROUTES.length);
    private final LongAdder[] buckets=adders(ROUTES.length*(LIMITS.length+1));
    private static LongAdder[] adders(int n){var a=new LongAdder[n];java.util.Arrays.setAll(a,i->new LongAdder());return a;}
    public void increment(Event event){if(event!=null)events[event.ordinal()].increment();}
    public void request(String classifiedRoute,int status,long durationNanos){
        int r=route(classifiedRoute),s=status>=100&&status<600?status/100-1:5;
        long duration=Math.max(0,durationNanos);int b=0;while(b<LIMITS.length&&duration>LIMITS[b])b++;
        requests[r*STATUSES.length+s].increment();buckets[r*(LIMITS.length+1)+b].increment();nanos[r].add(duration);
    }
    private static int route(String route){
        if(route==null)return 7;
        if(route.equals("auth"))return 0;if(route.equals("admin"))return 1;if(route.equals("library")||route.equals("problem.get"))return 2;
        if(route.startsWith("submission."))return 3;if(route.startsWith("selftest."))return 4;
        if(route.startsWith("content."))return 5;if(route.equals("classroom"))return 6;return 7;
    }
    public String render(){
        var out=new StringBuilder("# HELP forgeoj_api_events_total Process-local API events since start.\n# TYPE forgeoj_api_events_total counter\n");
        for(var event:Event.values())out.append("forgeoj_api_events_total{event=\"").append(event.name().toLowerCase(java.util.Locale.ROOT)).append("\"} ").append(events[event.ordinal()].sum()).append('\n');
        out.append("# HELP forgeoj_http_requests_total Requests by fixed route and response class.\n# TYPE forgeoj_http_requests_total counter\n");
        for(int r=0;r<ROUTES.length;r++)for(int s=0;s<STATUSES.length;s++)out.append("forgeoj_http_requests_total{route=\"").append(ROUTES[r]).append("\",status_class=\"").append(STATUSES[s]).append("\"} ").append(requests[r*STATUSES.length+s].sum()).append('\n');
        out.append("# HELP forgeoj_http_request_duration_seconds Request duration in seconds.\n# TYPE forgeoj_http_request_duration_seconds histogram\n");
        for(int r=0;r<ROUTES.length;r++){
            long cumulative=0;
            for(int b=0;b<=LIMITS.length;b++){
                cumulative+=buckets[r*(LIMITS.length+1)+b].sum();
                out.append("forgeoj_http_request_duration_seconds_bucket{route=\"").append(ROUTES[r]).append("\",le=\"").append(b==LIMITS.length?"+Inf":Double.toString(LIMITS[b]/1_000_000_000.0)).append("\"} ").append(cumulative).append('\n');
            }
            out.append("forgeoj_http_request_duration_seconds_count{route=\"").append(ROUTES[r]).append("\"} ").append(cumulative).append('\n');
            out.append("forgeoj_http_request_duration_seconds_sum{route=\"").append(ROUTES[r]).append("\"} ").append(nanos[r].sum()/1_000_000_000.0).append('\n');
        }
        return out.toString();
    }
}
