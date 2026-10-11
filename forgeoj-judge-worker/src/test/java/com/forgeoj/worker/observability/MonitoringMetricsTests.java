/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.observability;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MonitoringMetricsTests {
    @Test void onlyFixedLifecycleEventsAreExportedAndExecutionTimeIsInSeconds(){
        var m=new MonitoringMetrics();m.event("attempt.finished");m.event("private-source\"\n");m.event(null);m.execution(1_500_000_000);m.execution(-1);
        var text=m.render();assertTrue(text.contains("event=\"attempt_finished\"} 1"));assertFalse(text.contains("private-source"));assertTrue(text.contains("seconds_count 2"));assertTrue(text.contains("seconds_sum 1.5"));
    }
}
