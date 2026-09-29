package com.forgeoj.worker.sandbox;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

final class ExecutionBudget {

    private static final Duration MAXIMUM = Duration.ofMinutes(2);

    private final long deadlineNanos;
    private final LongSupplier nanoTime;

    static ExecutionBudget start(Duration requested) {
        return new ExecutionBudget(requested, System::nanoTime);
    }

    ExecutionBudget(Duration requested, LongSupplier nanoTime) {
        Objects.requireNonNull(requested, "requested");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (requested.isZero() || requested.isNegative()) {
            throw new IllegalArgumentException("Execution budget must be positive");
        }
        Duration boundedDuration = requested.compareTo(MAXIMUM) > 0 ? MAXIMUM : requested;
        long bounded = boundedDuration.toNanos();
        this.deadlineNanos = nanoTime.getAsLong() + bounded;
    }

    boolean expired() {
        return remainingNanos() <= 0;
    }

    Duration limit(Duration perCommandLimit) {
        Objects.requireNonNull(perCommandLimit, "perCommandLimit");
        long remaining = remainingNanos();
        if (remaining <= 0) {
            return Duration.ofMillis(1);
        }
        return Duration.ofNanos(Math.min(remaining, perCommandLimit.toNanos()));
    }

    private long remainingNanos() {
        return deadlineNanos - nanoTime.getAsLong();
    }
}
