package com.forgeoj.worker.messaging;

import com.forgeoj.worker.ForgeojJudgeWorkerApplication;
import com.forgeoj.worker.sandbox.DockerCommandExecutor;
import com.forgeoj.worker.sandbox.SandboxContainer;
import com.forgeoj.worker.sandbox.SandboxExecutionResult;
import com.forgeoj.worker.sandbox.SandboxRuntime;
import com.forgeoj.worker.snapshot.JudgeTaskSnapshot;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;

/** Test-only JVM entry point. Fault barriers are not present in the production artifact. */
public final class FaultWorkerProcess {

    public static void main(String[] args) {
        new SpringApplication(ForgeojJudgeWorkerApplication.class, FaultHooks.class).run(args);
    }

    static void barrier(String point) {
        System.out.println("FAULT_BARRIER=" + point);
        System.out.flush();
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test barrier interrupted");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultHooks {

        private final String mode = System.getenv("FORGEOJ_TEST_MODE");

        @Bean
        @Primary
        JudgeTaskRunner faultRunner(M0JudgeTaskRunner original) {
            return claim -> {
                if ("BEFORE_RUN".equals(mode)) barrier("BEFORE_RUN");
                original.run(claim);
                if ("BEFORE_ACK".equals(mode)) barrier("BEFORE_ACK");
            };
        }

        @Bean
        @Primary
        SandboxRuntime faultSandbox(@Qualifier("sandboxRuntime") SandboxRuntime original,
                DockerCommandExecutor executor) {
            return new SandboxRuntime() {
                @Override public void verifyAvailable() { original.verifyAvailable(); }
                @Override public void cleanupManagedContainers() { original.cleanupManagedContainers(); }
                @Override public SandboxContainer prepare(JudgeTaskSnapshot snapshot, String attemptId) { return original.prepare(snapshot, attemptId); }
                @Override public void cleanup(SandboxContainer container) { original.cleanup(container); }
                @Override public SandboxExecutionResult execute(JudgeTaskSnapshot snapshot, String attemptId) {
                    if ("WITH_SANDBOX".equals(mode)) {
                        SandboxContainer container = original.prepare(snapshot, attemptId);
                        var started = executor.execute(List.of("container", "start", container.identifier()),
                                Duration.ofSeconds(15), 4096);
                        if (started.exitCode() != 0 || started.timedOut()) {
                            throw new IllegalStateException("Test sandbox did not start");
                        }
                        barrier("WITH_SANDBOX");
                    }
                    return original.execute(snapshot, attemptId);
                }
            };
        }

        @EventListener(ApplicationReadyEvent.class)
        void ready(ApplicationReadyEvent event) {
            System.out.println("FAULT_PROCESS_READY");
            System.out.flush();
            Thread.ofVirtual().start(() -> {
                try {
                    if (System.in.read() == 'S') {
                        event.getApplicationContext().getBean(RabbitListenerEndpointRegistry.class).start();
                        System.out.println("FAULT_LISTENERS_STARTED");
                        System.out.flush();
                    }
                } catch (java.io.IOException ignored) {
                    // Parent closes stdin during process teardown.
                }
            });
        }
    }
}
