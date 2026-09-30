package com.forgeoj.worker.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JudgeTaskLeaseService {

    private final JudgeTaskMapper mapper;
    private final long leaseDurationSeconds;

    JudgeTaskLeaseService(
            JudgeTaskMapper mapper,
            @Value("${forgeoj.worker.lease-duration-seconds:30}") long leaseDurationSeconds) {
        if (leaseDurationSeconds < 1) {
            throw new IllegalArgumentException("Worker lease duration must be positive");
        }
        this.mapper = mapper;
        this.leaseDurationSeconds = leaseDurationSeconds;
    }

    @Transactional
    void renew(ClaimedJudgeTask claimedTask) {
        int taskUpdates =
                mapper.renewTaskLease(
                        claimedTask.message().taskId(),
                        claimedTask.message().submissionId(),
                        claimedTask.leaseToken(),
                        leaseDurationSeconds);
        int attemptUpdates =
                mapper.renewAttemptLease(
                        claimedTask.attemptId(),
                        claimedTask.message().taskId(),
                        claimedTask.leaseToken());
        if (taskUpdates != 1 || attemptUpdates != 1) {
            throw new LeaseOwnershipLostException("Judge task lease ownership was lost");
        }
    }
}
