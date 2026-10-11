/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.observability;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionDefinition;
class MonitoringSnapshotTests {
    @Test void databaseMetricFamiliesAreNotInterleavedAcrossKinds(){var s=new MonitoringSnapshot(mapper(),transaction(),Clock.systemUTC(),System::nanoTime);s.refresh();assertTrue(s.fresh());MonitoringMetricsTests.groupedFamilies(s.render());}
    private static AbstractPlatformTransactionManager transaction(){return new AbstractPlatformTransactionManager(){protected Object doGetTransaction(){return new Object();}protected void doBegin(Object t,TransactionDefinition d){assertTrue(d.isReadOnly());assertEquals(2,d.getTimeout());}protected void doCommit(DefaultTransactionStatus s){}protected void doRollback(DefaultTransactionStatus s){}};}
    private static MonitoringMapper mapper(){var m=mock(MonitoringMapper.class);when(m.tasks()).thenReturn(List.of(new MonitoringMapper.Group("FORMAL","DEAD_LETTER",2)));when(m.verdicts()).thenReturn(List.of());when(m.times()).thenReturn(List.of());when(m.rebuilds()).thenReturn(List.of());when(m.backlog()).thenReturn(new MonitoringMapper.Backlog(3,1,2,1,4,2,5));return m;}
    @Test void retainedCountsAreGaugesAndSamplingFailurePreservesFactsWithAnExplicitFailureFlag(){
        var m=mapper();var time=new AtomicLong(100);var s=new MonitoringSnapshot(m,transaction(),Clock.fixed(Instant.ofEpochSecond(123),ZoneOffset.UTC),time::get);
        assertFalse(s.fresh());s.refresh();assertTrue(s.fresh());var old=s.render();assertTrue(old.contains("kind=\"FORMAL\",state=\"DEAD_LETTER\"} 2"));assertTrue(old.contains("forgeoj_search_epoch_gap 5"));
        when(m.tasks()).thenThrow(new IllegalStateException("private-db-secret"));s.refresh();assertFalse(s.fresh());var failed=s.render();assertTrue(failed.contains("collection_success 0"));assertTrue(failed.contains("last_success_seconds 123"));assertTrue(failed.contains("state=\"DEAD_LETTER\"} 2"));assertFalse(failed.contains("private-db-secret"));
    }
    @Test void staleGoodSampleStopsReadinessWithoutPretendingCountsAreZero(){
        var time=new AtomicLong(0);var s=new MonitoringSnapshot(mapper(),transaction(),Clock.systemUTC(),time::get);s.refresh();time.set(45_000_000_000L);assertFalse(s.fresh());assertTrue(s.render().contains("snapshot_fresh 0"));assertTrue(s.render().contains("forgeoj_outbox_pending 3"));
    }
    @Test void unexpectedDatabaseDimensionsFailClosedInsteadOfCreatingAUserControlledSeries(){
        var m=mapper();when(m.tasks()).thenReturn(List.of(new MonitoringMapper.Group("private-owner-secret","QUEUED",1)));var s=new MonitoringSnapshot(m,transaction(),Clock.systemUTC(),System::nanoTime);s.refresh();assertFalse(s.fresh());assertFalse(s.render().contains("private-owner-secret"));
    }
}
