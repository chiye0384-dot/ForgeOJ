/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.observability;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MonitoringMetricsTests {
    static void groupedFamilies(String text){
        var closed=new java.util.HashSet<String>();String previous=null;
        for(String line:text.lines().toList()){
            if(line.startsWith("#")||line.isBlank())continue;
            String family=line.split("[ {]",2)[0];if(family.startsWith("forgeoj_http_request_duration_seconds"))family="forgeoj_http_request_duration_seconds";
            if(!family.equals(previous)){assertFalse(closed.contains(family),"Interleaved metric family "+family);if(previous!=null)closed.add(previous);previous=family;}
        }
    }
    @Test void eachMetricFamilyFormsOneContinuousGroupWithMetadata(){var text=new MonitoringMetrics().render();groupedFamilies(text);assertTrue(text.contains("# HELP forgeoj_http_request_duration_seconds "));}
    @Test void arbitraryRoutesCannotCreateLabelsAndHistogramsKeepUnitsAndCumulativeCounts(){
        var m=new MonitoringMetrics();m.request("submission.create",201,5_000_000);m.request("submission.get",503,30_000_000);
        m.request("private-source-secret\"\n",777,-1);m.increment(MonitoringMetrics.Event.CACHE_HIT);
        String text=m.render();assertFalse(text.contains("private-source-secret"));assertTrue(text.contains("route=\"other\",status_class=\"other\"} 1"));
        assertTrue(text.contains("route=\"submission\",le=\"0.005\"} 1"));assertTrue(text.contains("route=\"submission\",le=\"0.05\"} 2"));
        assertTrue(text.contains("route=\"submission\",le=\"+Inf\"} 2"));assertTrue(text.contains("seconds_count{route=\"submission\"} 2"));assertTrue(text.contains("seconds_sum{route=\"submission\"} 0.035"));
        assertTrue(text.contains("event=\"cache_hit\"} 1"));assertTrue(text.length()<262144);
    }
    @Test void concurrentRequestsRemainBoundedAndAreNotLost()throws Exception{
        var m=new MonitoringMetrics();try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{for(int n=0;n<2500;n++)m.request("auth",200,1_000_000);}));for(var future:futures)future.get();
        }
        String text=m.render();assertTrue(text.contains("route=\"auth\",status_class=\"2xx\"} 10000"));assertTrue(text.contains("route=\"auth\",le=\"+Inf\"} 10000"));assertTrue(text.contains("seconds_sum{route=\"auth\"} 10.0"));
    }
}
