package com.forgeoj.worker.sandbox;

import java.util.Objects;

public record SandboxContainer(String taskId, String attemptId, String name, String identifier) {

    public SandboxContainer {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(identifier, "identifier");
    }
}
