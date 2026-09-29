package com.forgeoj.worker.sandbox;

import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface DockerCommandExecutor {

    DockerCommandResult execute(
            List<String> arguments, Duration timeout, int outputLimitBytes);
}
