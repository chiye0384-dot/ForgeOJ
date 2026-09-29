package com.forgeoj.worker.sandbox;

public record DockerCommandResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean outputTruncated) {}
