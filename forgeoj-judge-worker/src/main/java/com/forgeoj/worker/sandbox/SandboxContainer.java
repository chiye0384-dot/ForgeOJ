package com.forgeoj.worker.sandbox;

import java.util.Objects;

public record SandboxContainer(String taskId, String name) {

    public SandboxContainer {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(name, "name");
    }
}
