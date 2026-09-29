package com.forgeoj.worker.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class ProcessBuilderDockerCommandExecutor implements DockerCommandExecutor {

    private static final Duration FORCE_STOP_WAIT = Duration.ofSeconds(5);

    private final String executable;

    ProcessBuilderDockerCommandExecutor(String executable) {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("Docker executable must be configured");
        }
        this.executable = executable;
    }

    @Override
    public DockerCommandResult execute(
            List<String> arguments, Duration timeout, int outputLimitBytes) {
        validateInvocation(arguments, timeout, outputLimitBytes);
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(executable);
        command.addAll(arguments);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
            process.getOutputStream().close();
        } catch (IOException failure) {
            throw new SandboxException("Could not start Docker CLI", failure);
        }

        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<BoundedOutput> stdout =
                    readers.submit(() -> readBounded(process.getInputStream(), outputLimitBytes));
            Future<BoundedOutput> stderr =
                    readers.submit(() -> readBounded(process.getErrorStream(), outputLimitBytes));

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(FORCE_STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }

            BoundedOutput out = stdout.get();
            BoundedOutput err = stderr.get();
            int exitCode = finished ? process.exitValue() : -1;
            return new DockerCommandResult(
                    exitCode,
                    out.text(),
                    err.text(),
                    !finished,
                    out.truncated() || err.truncated());
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SandboxException("Docker CLI invocation was interrupted", interrupted);
        } catch (ExecutionException failure) {
            process.destroyForcibly();
            throw new SandboxException("Could not read Docker CLI output", failure.getCause());
        }
    }

    private void validateInvocation(
            List<String> arguments, Duration timeout, int outputLimitBytes) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(timeout, "timeout");
        if (arguments.isEmpty() || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Docker invocation must have arguments and timeout");
        }
        if (outputLimitBytes <= 0) {
            throw new IllegalArgumentException("Docker output limit must be positive");
        }
        for (String argument : arguments) {
            if (argument == null || argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Docker argument is invalid");
            }
        }
    }

    private BoundedOutput readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream retained = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int writable = Math.min(read, Math.max(0, limit - total));
            if (writable > 0) {
                retained.write(buffer, 0, writable);
                total += writable;
            }
            if (writable < read) {
                truncated = true;
            }
        }
        return new BoundedOutput(retained.toString(StandardCharsets.UTF_8), truncated);
    }

    private record BoundedOutput(String text, boolean truncated) {}
}
