package com.forgeoj.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ExecutionBudgetTests {

    @Test
    void limitsEachCommandByTheRemainingSubmissionBudget() {
        AtomicLong now = new AtomicLong();
        ExecutionBudget budget = new ExecutionBudget(Duration.ofSeconds(3), now::get);

        assertThat(budget.limit(Duration.ofSeconds(2))).isEqualTo(Duration.ofSeconds(2));
        now.set(Duration.ofMillis(2500).toNanos());
        assertThat(budget.limit(Duration.ofSeconds(2))).isEqualTo(Duration.ofMillis(500));
        now.set(Duration.ofSeconds(3).toNanos());
        assertThat(budget.expired()).isTrue();
        assertThat(budget.limit(Duration.ofSeconds(2))).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    void capsUnexpectedlyLargeSubmissionBudget() {
        AtomicLong now = new AtomicLong();
        ExecutionBudget budget = new ExecutionBudget(Duration.ofMillis(Long.MAX_VALUE), now::get);

        now.set(Duration.ofMinutes(2).toNanos());
        assertThat(budget.expired()).isTrue();
    }
}
