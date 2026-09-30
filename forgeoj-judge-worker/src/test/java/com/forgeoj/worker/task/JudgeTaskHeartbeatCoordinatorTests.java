package com.forgeoj.worker.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.forgeoj.worker.messaging.JudgeTaskMessage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class JudgeTaskHeartbeatCoordinatorTests {

    private static final ClaimedJudgeTask CLAIM =
            new ClaimedJudgeTask(
                    new JudgeTaskMessage(
                            "b844c173-9436-4d35-a45c-a6f5041f7d20",
                            "a9987de8-880d-42af-b463-06ae4f7b9717",
                            "JUDGE_SUBMISSION",
                            1),
                    "c5862430-ab78-436f-b78c-bcdeb728503a",
                    1,
                    "b806756b-18bf-482b-83eb-9536d59d57aa",
                    "heartbeat-test-worker");

    @Test
    void periodicallyRenewsLeaseUntilSessionCloses() {
        JudgeTaskLeaseService leaseService = mock(JudgeTaskLeaseService.class);
        JudgeTaskHeartbeatCoordinator coordinator =
                new JudgeTaskHeartbeatCoordinator(leaseService, 10, 1);

        try (JudgeTaskHeartbeatCoordinator.HeartbeatSession ignored =
                coordinator.start(CLAIM)) {
            verify(leaseService, timeout(500).atLeastOnce()).renew(CLAIM);
        } finally {
            coordinator.shutdown();
        }
    }

    @Test
    void interruptsOwnerAndFailsSessionWhenLeaseCannotBeRenewed() throws Exception {
        JudgeTaskLeaseService leaseService = mock(JudgeTaskLeaseService.class);
        doThrow(new LeaseOwnershipLostException("lost"))
                .when(leaseService)
                .renew(CLAIM);
        JudgeTaskHeartbeatCoordinator coordinator =
                new JudgeTaskHeartbeatCoordinator(leaseService, 10, 1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> observed = new AtomicReference<>();
        Thread owner =
                new Thread(
                        () -> {
                            try (JudgeTaskHeartbeatCoordinator.HeartbeatSession ignored =
                                    coordinator.start(CLAIM)) {
                                started.countDown();
                                new CountDownLatch(1).await();
                            } catch (Throwable failure) {
                                observed.set(failure);
                            }
                        });

        try {
            owner.start();
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            owner.join(1000);

            assertThat(owner.isAlive()).isFalse();
            assertThat(observed.get()).isInstanceOf(InterruptedException.class);
            assertThat(observed.get().getSuppressed())
                    .hasAtLeastOneElementOfType(LeaseOwnershipLostException.class);
        } finally {
            owner.interrupt();
            owner.join(1000);
            coordinator.shutdown();
        }
    }
}
