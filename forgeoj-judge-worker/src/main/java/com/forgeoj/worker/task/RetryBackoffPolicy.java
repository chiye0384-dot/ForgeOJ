package com.forgeoj.worker.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class RetryBackoffPolicy {

    private final long baseSeconds;
    private final long maximumSeconds;

    RetryBackoffPolicy(
            @Value("${forgeoj.worker.retry.base-delay-seconds:5}") long baseSeconds,
            @Value("${forgeoj.worker.retry.max-delay-seconds:60}") long maximumSeconds) {
        if (baseSeconds < 1 || maximumSeconds < baseSeconds) {
            throw new IllegalArgumentException("Worker retry delays are invalid");
        }
        this.baseSeconds = baseSeconds;
        this.maximumSeconds = maximumSeconds;
    }

    long delaySeconds(int failedAttemptNo) {
        if (failedAttemptNo < 1) {
            throw new IllegalArgumentException("Attempt number must be positive");
        }
        long delay = baseSeconds;
        for (int attempt = 1; attempt < failedAttemptNo && delay < maximumSeconds; attempt++) {
            delay = Math.min(maximumSeconds, delay > maximumSeconds / 2 ? maximumSeconds : delay * 2);
        }
        return delay;
    }
}
