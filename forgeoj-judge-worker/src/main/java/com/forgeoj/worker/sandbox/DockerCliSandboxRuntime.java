package com.forgeoj.worker.sandbox;

import com.forgeoj.worker.snapshot.JudgeTaskSnapshot;
import com.forgeoj.worker.snapshot.JudgeTestCase;

import java.nio.charset.StandardCharsets;
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
    private static final Duration COMPILE_TIMEOUT = Duration.ofSeconds(15);
    private static final int CONTROL_OUTPUT_LIMIT = 64 * 1024;
    private static final int COMPILE_OUTPUT_LIMIT = 64 * 1024;
    private static final int DIAGNOSTIC_LIMIT = 2000;
    private static final String CONTAINER_WORKSPACE = "/workspace";
    private static final String USER_ID = "65532:65532";
    private static final String INIT_USER_ID = "65534:65534";

    private final DockerCommandExecutor executor;
    private final M0OutputComparator outputComparator = new M0OutputComparator();

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
        command.addAll(List.of("--user", INIT_USER_ID));
        command.addAll(List.of("--cpus", "1.0"));
        command.addAll(List.of("--memory", snapshot.memoryLimitMb() + "m"));
        command.addAll(List.of("--memory-swap", snapshot.memoryLimitMb() + "m"));
        command.addAll(List.of("--pids-limit", "64"));
        command.addAll(
                List.of("--tmpfs", "/workspace:rw,noexec,nosuid,nodev,size=64m,mode=1777"));
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
    public SandboxExecutionResult execute(JudgeTaskSnapshot snapshot) {
        validateSnapshot(snapshot);
        SandboxContainer container = null;
        Throwable primaryFailure = null;
        try {
            container = prepare(snapshot);
            runControl(
                    List.of("container", "start", container.name()),
                    "Could not start sandbox container");
            DockerCommandResult sourceTransfer =
                    executor.execute(
                            List.of(
                                    "container",
                                    "exec",
                                    "--interactive",
                                    "--user",
                                    INIT_USER_ID,
                                    container.name(),
                                    "dd",
                                    "of=" + CONTAINER_WORKSPACE + "/Main.java",
                                    "status=none"),
                            snapshot.sourceCode().getBytes(StandardCharsets.UTF_8),
                            CONTROL_TIMEOUT,
                            CONTROL_OUTPUT_LIMIT);
            requireSuccess(sourceTransfer, "Could not transfer source into sandbox container");

            DockerCommandResult compilation =
                    executor.execute(
                            List.of(
                                    "container",
                                    "exec",
                                    "--user",
                                    INIT_USER_ID,
                                    container.name(),
                                    "javac",
                                    "-encoding",
                                    "UTF-8",
                                    "-d",
                                    CONTAINER_WORKSPACE,
                                    CONTAINER_WORKSPACE + "/Main.java"),
                            COMPILE_TIMEOUT,
                            COMPILE_OUTPUT_LIMIT);
            if (compilation.timedOut()) {
                throw new SandboxException("Java compilation exceeded the platform timeout");
            }
            ensureContainerRunning(container);
            if (compilation.exitCode() != 0 || compilation.outputTruncated()) {
                return new SandboxExecutionResult(
                        SandboxOutcome.COMPILE_ERROR, safeCompilerDiagnostic(compilation));
            }

            runControl(
                    List.of(
                            "container",
                            "exec",
                            "--user",
                            "0:0",
                            container.name(),
                            "chmod",
                            "a-w",
                            CONTAINER_WORKSPACE),
                    "Could not freeze compiled sandbox workspace");

            ExecutionBudget executionBudget =
                    ExecutionBudget.start(totalExecutionBudget(snapshot));
            for (JudgeTestCase testCase : snapshot.testCases()) {
                if (executionBudget.expired()) {
                    return SandboxExecutionResult.of(SandboxOutcome.TIME_LIMIT_EXCEEDED);
                }
                SandboxExecutionResult result =
                        executeCase(snapshot, container, testCase, executionBudget);
                if (result.outcome() != SandboxOutcome.ACCEPTED) {
                    return result;
                }
            }
            return SandboxExecutionResult.of(SandboxOutcome.ACCEPTED);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            RuntimeException cleanupFailure = null;
            try {
                if (container != null) {
                    cleanup(container);
                }
            } catch (RuntimeException failure) {
                cleanupFailure = failure;
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(failure);
                }
            }
            if (primaryFailure == null && cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
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

    private SandboxExecutionResult executeCase(
            JudgeTaskSnapshot snapshot,
            SandboxContainer container,
            JudgeTestCase testCase,
            ExecutionBudget executionBudget) {
        String caseDirectory = "/tmp/case-" + String.format(Locale.ROOT, "%06d", testCase.ordinal());
        runControl(
                List.of(
                        "container",
                        "exec",
                        "--user",
                        USER_ID,
                        container.name(),
                        "mkdir",
                        "-m",
                        "700",
                        caseDirectory),
                "Could not create isolated test-case directory");

        DockerCommandResult execution;
        try {
            int heapMb = Math.max(32, snapshot.memoryLimitMb() / 2);
            execution =
                    executor.execute(
                            List.of(
                                    "container",
                                    "exec",
                                    "--interactive",
                                    "--user",
                                    USER_ID,
                                    "--env",
                                    "HOME=" + caseDirectory,
                                    "--env",
                                    "TMPDIR=" + caseDirectory,
                                    container.name(),
                                    "java",
                                    "-XX:ActiveProcessorCount=1",
                                    "-Xms16m",
                                    "-Xmx" + heapMb + "m",
                                    "-XX:MaxMetaspaceSize=48m",
                                    "-Dfile.encoding=UTF-8",
                                    "-Djava.io.tmpdir=" + caseDirectory,
                                    "-cp",
                                    CONTAINER_WORKSPACE,
                                    "Main"),
                            testCase.input(),
                            executionBudget.limit(Duration.ofMillis(snapshot.timeLimitMs())),
                            Math.toIntExact(snapshot.outputLimitBytes()));
        } finally {
            terminateUserProcesses(container);
            removeCaseDirectory(container, caseDirectory);
        }

        if (execution.outputTruncated()) {
            return SandboxExecutionResult.of(SandboxOutcome.OUTPUT_LIMIT_EXCEEDED);
        }
        if (execution.timedOut()) {
            return SandboxExecutionResult.of(SandboxOutcome.TIME_LIMIT_EXCEEDED);
        }
        ensureContainerRunning(container);
        if (execution.exitCode() != 0) {
            return SandboxExecutionResult.of(SandboxOutcome.RUNTIME_ERROR);
        }
        String expected = new String(testCase.expectedOutput(), StandardCharsets.UTF_8);
        if (!outputComparator.matches(execution.stdout(), expected)) {
            return SandboxExecutionResult.of(SandboxOutcome.WRONG_ANSWER);
        }
        return SandboxExecutionResult.of(SandboxOutcome.ACCEPTED);
    }

    private Duration totalExecutionBudget(JudgeTaskSnapshot snapshot) {
        long totalMillis;
        try {
            totalMillis =
                    Math.multiplyExact(
                            (long) snapshot.timeLimitMs(), snapshot.testCases().size());
        } catch (ArithmeticException overflow) {
            totalMillis = Long.MAX_VALUE;
        }
        return Duration.ofMillis(Math.max(1, totalMillis));
    }

    private void terminateUserProcesses(SandboxContainer container) {
        DockerCommandResult result =
                executor.execute(
                        List.of(
                                "container",
                                "exec",
                                "--user",
                                "0:0",
                                container.name(),
                                "/usr/bin/pkill",
                                "-KILL",
                                "-u",
                                "65532"),
                        CONTROL_TIMEOUT,
                        CONTROL_OUTPUT_LIMIT);
        if (result.timedOut() || result.outputTruncated() || result.exitCode() > 1) {
            throw new SandboxException("Could not terminate test-case processes");
        }
    }

    private void removeCaseDirectory(SandboxContainer container, String caseDirectory) {
        runControl(
                List.of(
                        "container",
                        "exec",
                        "--user",
                        "0:0",
                        container.name(),
                        "rm",
                        "-rf",
                        caseDirectory),
                "Could not clean isolated test-case directory");
    }

    private void ensureContainerRunning(SandboxContainer container) {
        DockerCommandResult inspected =
                executor.execute(
                        List.of(
                                "container",
                                "inspect",
                                "--format",
                                "{{.State.Running}}",
                                container.name()),
                        CONTROL_TIMEOUT,
                        CONTROL_OUTPUT_LIMIT);
        if (inspected.timedOut()
                || inspected.outputTruncated()
                || inspected.exitCode() != 0
                || !"true".equals(inspected.stdout().strip())) {
            throw new SandboxException("Sandbox container stopped unexpectedly");
        }
    }

    private void runControl(List<String> arguments, String failureMessage) {
        DockerCommandResult result =
                executor.execute(arguments, CONTROL_TIMEOUT, CONTROL_OUTPUT_LIMIT);
        requireSuccess(result, failureMessage);
    }

    private String safeCompilerDiagnostic(DockerCommandResult result) {
        String combined = (result.stdout() + "\n" + result.stderr()).strip();
        String sanitized = combined.replace(CONTAINER_WORKSPACE + "/", "");
        if (sanitized.length() <= DIAGNOSTIC_LIMIT) {
            return sanitized;
        }
        return sanitized.substring(0, DIAGNOSTIC_LIMIT);
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
        if (!"JAVA_21".equals(snapshot.language())) {
            throw new SandboxException("Unsupported sandbox language");
        }
        if (!"trim-trailing-whitespace-v1".equals(snapshot.comparisonRuleVersion())) {
            throw new SandboxException("Unsupported output comparison rule");
        }
        if (!"m0-v1".equals(snapshot.sandboxPolicyVersion())) {
            throw new SandboxException("Unsupported sandbox policy version");
        }
        if (snapshot.testCases().isEmpty()) {
            throw new SandboxException("Sandbox task has no test cases");
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
