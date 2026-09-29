package com.forgeoj.worker.snapshot;

import java.util.Objects;

public record JudgeTestCase(int ordinal, byte[] input, byte[] expectedOutput) {

    public JudgeTestCase {
        if (ordinal <= 0) {
            throw new IllegalArgumentException("Test case ordinal must be positive");
        }
        input = Objects.requireNonNull(input, "input").clone();
        expectedOutput = Objects.requireNonNull(expectedOutput, "expectedOutput").clone();
    }

    @Override
    public byte[] input() {
        return input.clone();
    }

    @Override
    public byte[] expectedOutput() {
        return expectedOutput.clone();
    }
}
