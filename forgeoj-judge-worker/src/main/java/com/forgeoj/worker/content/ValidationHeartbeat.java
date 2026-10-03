/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.content;

import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ValidationHeartbeat {
    private final ValidationLeaseService leases;
    private final long interval;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"forgeoj-validation-heartbeat");t.setDaemon(true);return t;});
    public ValidationHeartbeat(ValidationLeaseService leases,@Value("${forgeoj.worker.heartbeat-interval-millis:5000}") long interval,@Value("${forgeoj.worker.lease-duration-seconds:30}") long seconds) {
        if(interval<1 || seconds*1000<interval*3) throw new IllegalArgumentException("Invalid validation heartbeat interval");this.leases=leases;this.interval=interval;
    }
    public Session start(ValidationLeaseService.Claim claim) {var session=new Session(claim,Thread.currentThread());session.future=executor.scheduleAtFixedRate(session::beat,interval,interval,TimeUnit.MILLISECONDS);return session;}
    @PreDestroy void shutdown() {executor.shutdownNow();}
    public final class Session implements AutoCloseable {
        private final ValidationLeaseService.Claim claim;
        private final Thread owner;
        private ScheduledFuture<?> future;
        private boolean stopped,interrupted;
        private RuntimeException failure;
        private Session(ValidationLeaseService.Claim claim,Thread owner) {this.claim=claim;this.owner=owner;}
        private synchronized void beat() {
            if(stopped || failure!=null) return;
            try {leases.renew(claim);}catch(RuntimeException e) {failure=e;interrupted=true;owner.interrupt();}
        }
        @Override public synchronized void close() {
            stopped=true;if(future!=null) future.cancel(false);
            if(interrupted && Thread.currentThread()==owner) Thread.interrupted();
            if(failure!=null) throw new IllegalStateException("Validation heartbeat lost");
        }
    }
}
