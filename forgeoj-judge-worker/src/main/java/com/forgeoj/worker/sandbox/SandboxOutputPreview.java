/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.sandbox;

import java.util.List;

/** Successful execution output, never a comparison verdict or a formal Submission. */
public record SandboxOutputPreview(SandboxOutcome outcome,List<String> outputs) {
    public SandboxOutputPreview { outputs=List.copyOf(outputs);if(outcome!=SandboxOutcome.ACCEPTED && !outputs.isEmpty()) throw new IllegalArgumentException("Failed preview cannot retain partial output"); }
}
