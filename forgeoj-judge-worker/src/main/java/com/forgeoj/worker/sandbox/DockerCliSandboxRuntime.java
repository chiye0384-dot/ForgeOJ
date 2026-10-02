package com.forgeoj.worker.sandbox;

import com.forgeoj.worker.snapshot.JudgeTaskSnapshot;
import com.forgeoj.worker.snapshot.JudgeTestCase;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.databind.json.JsonMapper;

public final class DockerCliSandboxRuntime implements SandboxRuntime {

    private static final Pattern PINNED_IMAGE =
            Pattern.compile("[a-zA-Z0-9._/:\\-]+@sha256:[0-9a-f]{64}");
    private static final Pattern CONTAINER_ID = Pattern.compile("[0-9a-f]{64}");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration COMPILE_TIMEOUT = Duration.ofSeconds(15);
    private static final int CONTROL_OUTPUT_LIMIT = 64 * 1024;
    private static final int COMPILE_OUTPUT_LIMIT = 64 * 1024;
    private static final int DIAGNOSTIC_LIMIT = 2000;
    private static final String CONTAINER_WORKSPACE = "/workspace";
    private static final String USER_ID = "65532:65532";
    private static final String INIT_USER_ID = "65534:65534";

    private final DockerCommandExecutor executor;
    private final SandboxAttemptLookup attempts;
    private final M0OutputComparator outputComparator = new M0OutputComparator();

    public DockerCliSandboxRuntime(DockerCommandExecutor executor) {
        this(executor, (taskId, attemptId) -> false);
    }

    public DockerCliSandboxRuntime(DockerCommandExecutor executor, SandboxAttemptLookup attempts) {
        this.executor = executor;
        this.attempts = attempts;
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
        for (String identifier : listed.stdout().lines().map(String::strip).toList()) {
            if (!CONTAINER_ID.matcher(identifier).matches()) continue;
            SandboxContainer container = inspectOwnedContainer(identifier);
            // RUNNING, missing/foreign database records and legacy unlabeled attempts are retained.
            // Lease expiry alone is not sufficient: a successful claim must first fence the old attempt.
            if (container != null && attempts.isClosed(container.taskId(), container.attemptId())) {
                removeByIdentifier(container.identifier());
            }
        }
    }

    @Override
    public SandboxContainer prepare(JudgeTaskSnapshot snapshot, String attemptId) {
        validateSnapshot(snapshot);
        String containerName = managedName(snapshot.taskId(), attemptId);

        List<String> command = new ArrayList<>();
        command.addAll(List.of("container", "create"));
        command.add("--init");
        command.addAll(List.of("--name", containerName));
        command.addAll(List.of("--label", "com.forgeoj.managed=true"));
        command.addAll(List.of("--label", "com.forgeoj.task-id=" + snapshot.taskId()));
        command.addAll(List.of("--label", "com.forgeoj.attempt-id=" + attemptId));
        command.addAll(List.of("--network", "none"));
        command.addAll(List.of("--ipc", "none"));
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
        return new SandboxContainer(snapshot.taskId(), attemptId, containerName, created.stdout().strip());
    }

    @Override
    public SandboxExecutionResult execute(JudgeTaskSnapshot snapshot, String attemptId) {
        validateSnapshot(snapshot);
        SandboxContainer container = null;
        Throwable primaryFailure = null;
        try {
            container = prepare(snapshot, attemptId);
            runControl(
                    List.of("container", "start", container.identifier()),
                    "Could not start sandbox container");
            DockerCommandResult sourceTransfer =
                    executor.execute(
                            List.of(
                                    "container",
                                    "exec",
                                    "--interactive",
                                    "--user",
                                    INIT_USER_ID,
                                    container.identifier(),
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
                                    container.identifier(),
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
                            container.identifier(),
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
        if (!managedName(container.taskId(), container.attemptId()).equals(container.name())
                || !CONTAINER_ID.matcher(container.identifier()).matches()) {
            throw new SandboxException("Refusing to remove an unmanaged container");
        }
        SandboxContainer actual = inspectOwnedContainer(container.identifier());
        if (actual == null) {
            if (isAbsent(container.identifier())) return;
            throw new SandboxException("Refusing to remove a container with mismatched ownership");
        }
        if (!actual.equals(container)) throw new SandboxException("Sandbox ownership changed");
        removeByIdentifier(container.identifier());
    }

    private String managedName(String taskId, String attemptId) {
        if (taskId == null || attemptId == null) throw new InvalidSandboxConfigurationException("Sandbox identity is missing");
        try {
            String task = UUID.fromString(taskId).toString();
            String attempt = UUID.fromString(attemptId).toString();
            if (!task.equals(taskId) || !attempt.equals(attemptId)) throw new IllegalArgumentException();
            return "forgeoj-" + task.replace("-", "") + "-" + attempt.replace("-", "");
        } catch (IllegalArgumentException invalid) {
            throw new InvalidSandboxConfigurationException("Sandbox identity is invalid", invalid);
        }
    }

    private SandboxContainer inspectOwnedContainer(String identifier) {
        DockerCommandResult inspected = executor.execute(List.of("container", "inspect", "--format",
                "{{.Id}}|{{.Name}}|{{json .Config.Labels}}", identifier), CONTROL_TIMEOUT, CONTROL_OUTPUT_LIMIT);
        if (inspected.exitCode() != 0 && !inspected.timedOut() && !inspected.outputTruncated()
                && isAbsent(identifier)) return null;
        requireSuccess(inspected, "Could not inspect sandbox ownership");
        String[] parts = inspected.stdout().strip().split("\\|", 3);
        if (parts.length != 3 || !identifier.equals(parts[0])) return null;
        try {
            var labels = JSON.readTree(parts[2]);
            if (!"true".equals(labels.path("com.forgeoj.managed").asText())) return null;
            String taskId = labels.path("com.forgeoj.task-id").asText();
            String attemptId = labels.path("com.forgeoj.attempt-id").asText();
            String name = managedName(taskId, attemptId);
            if (!parts[1].equals("/" + name)) return null;
            return new SandboxContainer(taskId, attemptId, name, identifier);
        } catch (InvalidSandboxConfigurationException | IllegalArgumentException invalidMetadata) {
            return null;
        } catch (tools.jackson.core.JacksonException invalidJson) {
            return null;
        }
    }

    private boolean isAbsent(String identifier) {
        DockerCommandResult listed = executor.execute(List.of("container", "ls", "--all", "--quiet", "--no-trunc",
                "--filter", "id=" + identifier), CONTROL_TIMEOUT, CONTROL_OUTPUT_LIMIT);
        requireSuccess(listed, "Could not check sandbox removal");
        return listed.stdout().isBlank();
    }

    private void removeByIdentifier(String identifier) {
        DockerCommandResult removed =
                executor.execute(
                        List.of("container", "rm", "--force", "--volumes", identifier),
                        CONTROL_TIMEOUT,
                        CONTROL_OUTPUT_LIMIT);
        if (removed.exitCode() != 0 && !removed.timedOut() && !removed.outputTruncated() && isAbsent(identifier)) return;
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
                        container.identifier(),
                        "mkdir",
                        "-m",
                        "700",
                        caseDirectory),
                "Could not create isolated test-case directory");

        DockerCommandResult execution;
        SandboxResourceEvents before = readResourceEvents(container);
        SandboxResourceEvents after;
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
                                    container.identifier(),
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
            try {
                after = readResourceEvents(container);
            } finally {
                cleanupUserTemporaryFiles(container);
            }
        }

        SandboxOutcome violation = after.violationSince(before);
        if (violation != null) return SandboxExecutionResult.of(violation);
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

    private SandboxResourceEvents readResourceEvents(SandboxContainer container) {
        return SandboxResourceEvents.parse(
                readResourceFile(container, "/sys/fs/cgroup/memory.events"),
                readResourceFile(container, "/sys/fs/cgroup/pids.events"));
    }

    private String readResourceFile(SandboxContainer container, String path) {
        DockerCommandResult result = executor.execute(
                List.of("container", "exec", "--user", INIT_USER_ID, container.identifier(),
                        "/usr/bin/cat", path), CONTROL_TIMEOUT, CONTROL_OUTPUT_LIMIT);
        requireSuccess(result, "Could not read sandbox resource evidence");
        return result.stdout();
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
        for (int sweep = 0; sweep < 5; sweep++) {
            killUserProcesses(container);
            DockerCommandResult remaining = executor.execute(
                    List.of("container", "exec", "--user", INIT_USER_ID, container.identifier(),
                            "/usr/bin/pgrep", "-u", "65532"), CONTROL_TIMEOUT, CONTROL_OUTPUT_LIMIT);
            if (remaining.timedOut() || remaining.outputTruncated() || remaining.exitCode() > 1) {
                throw new SandboxException("Could not verify test-case process cleanup");
            }
            if (remaining.exitCode() == 1) return;
        }
        // Never let another case run, or walk user-controlled paths, with surviving processes.
        throw new SandboxException("Test-case processes survived cleanup");
    }

    private void killUserProcesses(SandboxContainer container) {
        DockerCommandResult result =
                executor.execute(
                        List.of(
                                "container",
                                "exec",
                                "--user",
                                USER_ID,
                                container.identifier(),
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

    private void cleanupUserTemporaryFiles(SandboxContainer container) {
        // Only owned, real directories are chmod targets. GNU chmod -R does not traverse
        // nested symlinks; user processes have already been killed before this walk.
        runControl(
                List.of("container", "exec", "--user", USER_ID, container.identifier(),
                        "find", "/tmp", "-mindepth", "1", "-maxdepth", "1", "-type", "d", "-uid", "65532",
                        "-exec", "chmod", "-R", "u+rwX", "--", "{}", "+"),
                "Could not restore owned temporary directory permissions");
        runControl(
                List.of(
                        "container",
                        "exec",
                        "--user",
                        USER_ID,
                        container.identifier(),
                        "find", "/tmp", "-mindepth", "1", "-maxdepth", "1", "-uid", "65532",
                        "-exec", "rm", "-rf", "--", "{}", "+"),
                "Could not clean owned temporary files");
    }

    private void ensureContainerRunning(SandboxContainer container) {
        DockerCommandResult inspected =
                executor.execute(
                        List.of(
                                "container",
                                "inspect",
                                "--format",
                                "{{.State.Running}}",
                                container.identifier()),
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
            throw new InvalidSandboxConfigurationException("Task identifier is invalid", invalid);
        }
        if (!PINNED_IMAGE.matcher(snapshot.javaImageDigest()).matches()) {
            throw new InvalidSandboxConfigurationException(
                    "Sandbox image must be pinned by SHA-256 digest");
        }
        if (snapshot.memoryLimitMb() < 64 || snapshot.memoryLimitMb() > 2048) {
            throw new InvalidSandboxConfigurationException(
                    "Sandbox memory limit is outside M0 policy");
        }
        if (snapshot.timeLimitMs() < 100 || snapshot.timeLimitMs() > 30_000) {
            throw new InvalidSandboxConfigurationException(
                    "Sandbox time limit is outside M0 policy");
        }
        if (snapshot.outputLimitBytes() < 1 || snapshot.outputLimitBytes() > 16L * 1024 * 1024) {
            throw new InvalidSandboxConfigurationException(
                    "Sandbox output limit is outside M0 policy");
        }
        if (!"JAVA_21".equals(snapshot.language())) {
            throw new InvalidSandboxConfigurationException("Unsupported sandbox language");
        }
        if (!"trim-trailing-whitespace-v1".equals(snapshot.comparisonRuleVersion())) {
            throw new InvalidSandboxConfigurationException(
                    "Unsupported output comparison rule");
        }
        if (!"m0-v1".equals(snapshot.sandboxPolicyVersion())) {
            throw new InvalidSandboxConfigurationException(
                    "Unsupported sandbox policy version");
        }
        if (snapshot.testCases().isEmpty()) {
            throw new InvalidSandboxConfigurationException("Sandbox task has no test cases");
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
