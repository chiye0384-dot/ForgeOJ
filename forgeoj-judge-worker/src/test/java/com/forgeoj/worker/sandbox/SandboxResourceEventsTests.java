package com.forgeoj.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SandboxResourceEventsTests {
    @Test void comparesPerCaseCountersInsteadOfTreatingOldEventsAsNewViolations() {
        var before = SandboxResourceEvents.parse("oom 2\noom_kill 1\n", "max 3\n");
        assertThat(before.violationSince(before)).isNull();
        assertThat(SandboxResourceEvents.parse("oom 3\noom_kill 1\n", "max 3\n")
                .violationSince(before)).isEqualTo(SandboxOutcome.MEMORY_LIMIT_EXCEEDED);
        assertThat(SandboxResourceEvents.parse("oom 3\noom_kill 2\n", "max 3\n")
                .violationSince(before)).isEqualTo(SandboxOutcome.MEMORY_LIMIT_EXCEEDED);
    }

    @Test void pidViolationHasPriorityWhenBothLimitsAreReached() {
        var before = SandboxResourceEvents.parse("oom 0\noom_kill 0\n", "max 0\n");
        assertThat(SandboxResourceEvents.parse("oom 1\noom_kill 1\n", "max 1\n")
                .violationSince(before)).isEqualTo(SandboxOutcome.SECURITY_VIOLATION);
    }

    @Test void ignoresReclaimEventsAndAllowsNewKernelFields() {
        var events = SandboxResourceEvents.parse("max 999\nhigh 2\noom 0\noom_kill 0\nnew_kernel_counter 4\n", "max 0\n");
        assertThat(events.violationSince(SandboxResourceEvents.parse("oom 0\noom_kill 0", "max 0"))).isNull();
    }

    @Test void rejectsMissingDuplicatedNegativeAndOverflowingRequiredCounters() {
        for (String invalid : new String[]{"oom_kill 0", "oom 0\noom 0\noom_kill 0",
                "oom -1\noom_kill 0", "oom 9223372036854775808\noom_kill 0", "oom zero\noom_kill 0"}) {
            assertThatThrownBy(() -> SandboxResourceEvents.parse(invalid, "max 0"))
                    .isInstanceOf(SandboxException.class)
                    .hasMessage("Sandbox resource evidence is invalid");
        }
        assertThatThrownBy(() -> SandboxResourceEvents.parse("oom 0\noom_kill 0", ""))
                .isInstanceOf(SandboxException.class);
    }

    @Test void rejectsCounterResetInsteadOfGuessingAUserVerdict() {
        var before = SandboxResourceEvents.parse("oom 2\noom_kill 1", "max 3");
        var after = SandboxResourceEvents.parse("oom 1\noom_kill 1", "max 3");
        assertThatThrownBy(() -> after.violationSince(before)).isInstanceOf(SandboxException.class);
    }

    @Test void externalOomKillAloneIsAPlatformFailureRatherThanMemoryLimitVerdict() {
        var before = SandboxResourceEvents.parse("oom 0\noom_kill 0", "max 0");
        var after = SandboxResourceEvents.parse("oom 0\noom_kill 1", "max 0");
        assertThatThrownBy(() -> after.violationSince(before)).isInstanceOf(SandboxException.class)
                .hasMessage("Sandbox memory termination has no local limit evidence");
    }
}
