package com.forgeoj.worker.task;

import jakarta.annotation.PreDestroy;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JudgeTaskHeartbeatCoordinator {

    private final JudgeTaskLeaseService leaseService;
    private final long intervalMillis;
    private final ScheduledExecutorService executor;

    JudgeTaskHeartbeatCoordinator(
            JudgeTaskLeaseService leaseService,
            @Value("${forgeoj.worker.heartbeat-interval-millis:5000}") long intervalMillis,
            @Value("${forgeoj.worker.lease-duration-seconds:30}") long leaseDurationSeconds) {
        if (intervalMillis < 1 || leaseDurationSeconds * 1000 < intervalMillis * 3) {
            throw new IllegalArgumentException(
                    "Worker lease must be at least three heartbeat intervals");
        }
        this.leaseService = leaseService;
        this.intervalMillis = intervalMillis;
        ThreadFactory threadFactory =
                runnable -> {
                    Thread thread = new Thread(runnable, "forgeoj-lease-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                };
        this.executor = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    public HeartbeatSession start(ClaimedJudgeTask claimedTask) {
        Session session = new Session(claimedTask, Thread.currentThread());
        session.future =
                executor.scheduleAtFixedRate(
                        session::heartbeat,
                        intervalMillis,
                        intervalMillis,
                        TimeUnit.MILLISECONDS);
        return session;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public interface HeartbeatSession extends AutoCloseable {

        @Override
        void close();
    }

    private final class Session implements HeartbeatSession {

        private final Object monitor = new Object();
        private final ClaimedJudgeTask claimedTask;
        private final Thread ownerThread;
        private ScheduledFuture<?> future;
        private RuntimeException failure;
        private boolean stopped;
        private boolean interruptedOwner;

        private Session(ClaimedJudgeTask claimedTask, Thread ownerThread) {
            this.claimedTask = claimedTask;
            this.ownerThread = ownerThread;
        }

        private void heartbeat() {
            synchronized (monitor) {
                if (stopped || failure != null) {
                    return;
                }
                try {
                    leaseService.renew(claimedTask);
                } catch (RuntimeException renewalFailure) {
                    failure = renewalFailure;
                    interruptedOwner = true;
                    ownerThread.interrupt();
                }
            }
        }

        @Override
        public void close() {
            synchronized (monitor) {
                stopped = true;
                if (future != null) {
                    future.cancel(false);
                }
                if (interruptedOwner && Thread.currentThread() == ownerThread) {
                    Thread.interrupted();
                }
                if (failure != null) {
                    throw new LeaseOwnershipLostException(
                            "Judge task heartbeat could not renew execution ownership");
                }
            }
        }
    }
}
