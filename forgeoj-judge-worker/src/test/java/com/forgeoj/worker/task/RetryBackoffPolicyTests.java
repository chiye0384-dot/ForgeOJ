package com.forgeoj.worker.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RetryBackoffPolicyTests {

    @Test
    void doublesDelayByAttemptAndStopsAtConfiguredMaximum() {
        RetryBackoffPolicy policy = new RetryBackoffPolicy(5, 12);

        assertThat(policy.delaySeconds(1)).isEqualTo(5);
        assertThat(policy.delaySeconds(2)).isEqualTo(10);
        assertThat(policy.delaySeconds(3)).isEqualTo(12);
        assertThat(policy.delaySeconds(30)).isEqualTo(12);
    }

    @Test
    void rejectsInvalidConfigurationAndAttemptNumbers() {
        assertThatThrownBy(() -> new RetryBackoffPolicy(0, 60))
                .isInstanceOf(IllegalArgumentException.class);
        RetryBackoffPolicy policy = new RetryBackoffPolicy(5, 60);
        assertThatThrownBy(() -> policy.delaySeconds(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
