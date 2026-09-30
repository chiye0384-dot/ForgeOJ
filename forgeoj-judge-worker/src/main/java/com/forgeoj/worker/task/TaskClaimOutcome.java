package com.forgeoj.worker.task;

public enum TaskClaimOutcome {
    CLAIMED,
    DEFERRED,
    DUPLICATE,
    REJECTED,
    EXHAUSTED
}
