package com.forgeoj.worker.sandbox;

import com.forgeoj.worker.snapshot.JudgeTaskSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class DockerCliSandboxRuntime implements SandboxRuntime {

    private static final Pattern PINNED_IMAGE =
            Pattern.compile("[a-zA-Z0-9._/:\\-]+@sha256:[0-9a-f]{64}");
    private static final Pattern CONTAINER_ID = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MANAGED_NAME = Pattern.compile("forgeoj-[0-9a-f]{32}");
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(15);
    private static final int CONTROL_OUTPUT_LIMIT = 64 * 1024;

    private final DockerCommandExecutor executor;

    public DockerCliSandboxRuntime(DockerCommandExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void verifyAvailable() {
        DockerCommandResult result =
                executor.execute(
                        List.of("version", "--format", "{{.Server.Os}}"),
                        CONTROL_TIMEOUT,
                        CONTROL_OUTPUT_LIMIT);
        if (result.timedOut()
                || result.outputTruncated()
                || result.exitCode() != 0
                || !"linux".equals(result.stdout().strip().toLowerCase(Locale.ROOT))) {
            throw new SandboxException("Docker daemon is unavailable or is not a Linux engine");
        }
    }

    @Override
    public void cleanupManagedContainers() {
        DockerCommandResult listed =
                executor.execute(
                        List.of(
                                "container",
                                "ls",
                                "--all",
                                "--quiet",
                                "--no-trunc",
                                "--filter",
                                "label=com.forgeoj.managed=true"),
                        CONTROL_TIMEOUT,
                        CONTROL_OUTPUT_LIMIT);
        requireSuccess(listed, "Could not list managed sandbox containers");
        if (listed.stdout().isBlank()) {
            return;
        }
        Arrays.stream(listed.stdout().split("\\R"))
                .map(String::strip)
                .filter(identifier -> CONTAINER_ID.matcher(identifier).matches())
                .forEach(this::removeByIdentifier);
    }

    @Override
    public SandboxContainer prepare(JudgeTaskSnapshot snapshot) {
        validateSnapshot(snapshot);
        String compactTaskId = UUID.fromString(snapshot.taskId()).toString().replace("-", "");
        String containerName = "forgeoj-" + compactTaskId;

        List<String> command = new ArrayList<>();
        command.addAll(List.of("container", "create"));
        command.addAll(List.of("--name", containerName));
        command.addAll(List.of("--label", "com.forgeoj.managed=true"));
        command.addAll(List.of("--label", "com.forgeoj.task-id=" + snapshot.taskId()));
        command.addAll(List.of("--network", "none"));
        command.add("--read-only");
        command.addAll(List.of("--cap-drop", "ALL"));
        command.addAll(List.of("--security-opt", "no-new-privileges"));
        command.addAll(List.of("--user", "65532:65532"));
        command.addAll(List.of("--cpus", "1.0"));
        command.addAll(List.of("--memory", snapshot.memoryLimitMb() + "m"));
        command.addAll(List.of("--memory-swap", snapshot.memoryLimitMb() + "m"));
        command.addAll(List.of("--pids-limit", "64"));
        command.addAll(
                List.of("--tmpfs", "/workspace:rw,noexec,nosuid,nodev,size=64m"));
        command.addAll(List.of("--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=16m"));
        command.addAll(List.of("--workdir", "/workspace"));
        command.addAll(List.of("--entrypoint", "/usr/bin/sleep"));
        command.add(snapshot.javaImageDigest());
        command.add("infinity");

        DockerCommandResult created =
                executor.execute(command, CONTROL_TIMEOUT, CONTROL_OUTPUT_LIMIT);
        requireSuccess(created, "Could not create sandbox container");
        if (!CONTAINER_ID.matcher(created.stdout().strip()).matches()) {
            throw new SandboxException("Docker returned an invalid container identifier");
        }
        return new SandboxContainer(snapshot.taskId(), containerName);
    }

    @Override
    public void cleanup(SandboxContainer container) {
        String expectedName =
                "forgeoj-"
                        + UUID.fromString(container.taskId()).toString().replace("-", "");
        if (!expectedName.equals(container.name())
                || !MANAGED_NAME.matcher(container.name()).matches()) {
            throw new SandboxException("Refusing to remove an unmanaged container");
        }
        removeByIdentifier(container.name());
    }

    private void removeByIdentifier(String identifier) {
        DockerCommandResult removed =
                executor.execute(
                        List.of("container", "rm", "--force", "--volumes", identifier),
                        CONTROL_TIMEOUT,
                        CONTROL_OUTPUT_LIMIT);
        requireSuccess(removed, "Could not remove managed sandbox container");
    }

    private void validateSnapshot(JudgeTaskSnapshot snapshot) {
        try {
            UUID.fromString(snapshot.taskId());
        } catch (IllegalArgumentException invalid) {
            throw new SandboxException("Task identifier is invalid", invalid);
        }
        if (!PINNED_IMAGE.matcher(snapshot.javaImageDigest()).matches()) {
            throw new SandboxException("Sandbox image must be pinned by SHA-256 digest");
        }
        if (snapshot.memoryLimitMb() < 64 || snapshot.memoryLimitMb() > 2048) {
            throw new SandboxException("Sandbox memory limit is outside M0 policy");
        }
        if (snapshot.timeLimitMs() < 100 || snapshot.timeLimitMs() > 30_000) {
            throw new SandboxException("Sandbox time limit is outside M0 policy");
        }
        if (snapshot.outputLimitBytes() < 1 || snapshot.outputLimitBytes() > 16L * 1024 * 1024) {
            throw new SandboxException("Sandbox output limit is outside M0 policy");
        }
        if (!"m0-v1".equals(snapshot.sandboxPolicyVersion())) {
            throw new SandboxException("Unsupported sandbox policy version");
        }
    }

    private void requireSuccess(DockerCommandResult result, String message) {
        if (result.timedOut()
                || result.outputTruncated()
                || result.exitCode() != 0) {
            throw new SandboxException(message);
        }
    }
}
