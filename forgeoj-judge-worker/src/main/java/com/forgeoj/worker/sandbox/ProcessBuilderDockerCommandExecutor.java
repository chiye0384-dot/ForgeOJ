package com.forgeoj.worker.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
        return execute(arguments, new byte[0], timeout, outputLimitBytes);
    }

    @Override
    public DockerCommandResult execute(
            List<String> arguments,
            byte[] standardInput,
            Duration timeout,
            int outputLimitBytes) {
        validateInvocation(arguments, timeout, outputLimitBytes);
        byte[] inputBytes = standardInput == null ? new byte[0] : standardInput.clone();
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(executable);
        command.addAll(arguments);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException failure) {
            throw new SandboxException("Could not start Docker CLI", failure);
        }

        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicInteger remainingOutput = new AtomicInteger(outputLimitBytes);
            AtomicBoolean outputTruncated = new AtomicBoolean();
            AtomicBoolean stopRequested = new AtomicBoolean();
            Future<BoundedOutput> stdout =
                    readers.submit(
                            () ->
                                    readBounded(
                                            process.getInputStream(),
                                            remainingOutput,
                                            outputTruncated,
                                            stopRequested,
                                            process));
            Future<BoundedOutput> stderr =
                    readers.submit(
                            () ->
                                    readBounded(
                                            process.getErrorStream(),
                                            remainingOutput,
                                            outputTruncated,
                                            stopRequested,
                                            process));
            Future<?> inputWriter = readers.submit(() -> writeInput(process, inputBytes));

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                stopRequested.set(true);
                process.destroyForcibly();
                process.waitFor(FORCE_STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }

            BoundedOutput out = stdout.get();
            BoundedOutput err = stderr.get();
            inputWriter.get();
            int exitCode = finished ? process.exitValue() : -1;
            return new DockerCommandResult(
                    exitCode,
                    out.text(),
                    err.text(),
                    !finished,
                    outputTruncated.get() || out.truncated() || err.truncated());
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

    private void writeInput(Process process, byte[] standardInput) {
        try (var output = process.getOutputStream()) {
            output.write(standardInput);
        } catch (IOException ignoredWhenProcessStopsReading) {
            // The child may exit before consuming all input. Its exit status is authoritative.
        }
    }

    private BoundedOutput readBounded(
            InputStream input,
            AtomicInteger remaining,
            AtomicBoolean sharedTruncated,
            AtomicBoolean stopRequested,
            Process process)
            throws IOException {
        ByteArrayOutputStream retained = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        try {
            int read;
            while ((read = input.read(buffer)) != -1) {
                int writable = reserve(remaining, read);
                if (writable > 0) {
                    retained.write(buffer, 0, writable);
                }
                if (writable < read) {
                    truncated = true;
                    sharedTruncated.set(true);
                    stopRequested.set(true);
                    process.destroyForcibly();
                }
            }
        } catch (IOException failure) {
            if (!stopRequested.get()) {
                throw failure;
            }
        }
        return new BoundedOutput(retained.toString(StandardCharsets.UTF_8), truncated);
    }

    private int reserve(AtomicInteger remaining, int requested) {
        while (true) {
            int available = remaining.get();
            if (available <= 0) {
                return 0;
            }
            int granted = Math.min(available, requested);
            if (remaining.compareAndSet(available, available - granted)) {
                return granted;
            }
        }
    }

    private record BoundedOutput(String text, boolean truncated) {}
}
