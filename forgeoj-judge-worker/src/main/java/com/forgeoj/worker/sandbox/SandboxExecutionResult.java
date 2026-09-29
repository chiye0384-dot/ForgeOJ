package com.forgeoj.worker.sandbox;

import java.util.Objects;

public record SandboxExecutionResult(SandboxOutcome outcome, String diagnosticMessage) {

    public SandboxExecutionResult {
        Objects.requireNonNull(outcome, "outcome");
        diagnosticMessage = diagnosticMessage == null ? "" : diagnosticMessage;
    }

    public static SandboxExecutionResult of(SandboxOutcome outcome) {
        return new SandboxExecutionResult(outcome, "");
    }
}
