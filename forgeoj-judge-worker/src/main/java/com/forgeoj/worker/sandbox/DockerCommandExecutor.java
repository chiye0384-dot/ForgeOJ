package com.forgeoj.worker.sandbox;

import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface DockerCommandExecutor {

    DockerCommandResult execute(
            List<String> arguments, Duration timeout, int outputLimitBytes);

    default DockerCommandResult execute(
            List<String> arguments,
            byte[] standardInput,
            Duration timeout,
            int outputLimitBytes) {
        if (standardInput != null && standardInput.length > 0) {
            throw new UnsupportedOperationException("This executor does not accept standard input");
        }
        return execute(arguments, timeout, outputLimitBytes);
    }
}
