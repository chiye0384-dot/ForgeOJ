package com.forgeoj.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeoj.worker.snapshot.JudgeTaskSnapshot;
import com.forgeoj.worker.snapshot.JudgeTestCase;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Bounded adversarial programs run only inside the production restricted sandbox. */
class DockerSandboxSecurityIntegrationTests {

    private static final String IMAGE = "eclipse-temurin:21.0.12_8-jdk-jammy"
            + "@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438";
    private final DockerCommandExecutor docker = new ProcessBuilderDockerCommandExecutor("docker");
    private DockerCommandResult lastExecution;
    private String transferredInput;

    @BeforeAll static void ensureImage() {
        DockerCliSandboxRuntimeIntegrationTests.ensurePinnedImageIsPresent();
    }

    @Test void writableCaseFilesAreRemovedBeforeTheNextJvm() {
        assertAccepted("""
                import java.nio.file.*;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        String value = new String(System.in.readAllBytes()).strip();
                        Path current = Path.of(System.getProperty("java.io.tmpdir"));
                        if (value.equals("first")) {
                            Files.createDirectories(current.resolve("nested"));
                            Files.writeString(current.resolve("nested/private.txt"), "CASE_ONE_PRIVATE");
                        } else if (Files.exists(Path.of("/tmp/case-000001"))) {
                            throw new AssertionError("Previous case files survived");
                        }
                        System.out.println("OK");
                    }
                }
                """, List.of(testCase(1, "first", "OK"), testCase(2, "second", "OK")));
    }

    @Test void backgroundDescendantCannotSurviveIntoTheNextCase() {
        assertAccepted("""
                import java.nio.file.*;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        String value = new String(System.in.readAllBytes()).strip();
                        if (value.equals("first")) {
                            new ProcessBuilder("/usr/bin/sleep", "20").start();
                        } else {
                            try (var proc = Files.list(Path.of("/proc"))) {
                                for (Path entry : proc.filter(p -> p.getFileName().toString().matches("[0-9]+" )).toList()) {
                                    try {
                                        if (Files.readString(entry.resolve("comm")).strip().equals("sleep")
                                                && Files.readString(entry.resolve("status")).contains("Uid:\\t65532\\t")) {
                                            throw new AssertionError("Previous case child survived");
                                        }
                                    } catch (NoSuchFileException exited) { }
                                }
                            }
                        }
                        System.out.println("OK");
                    }
                }
                """, List.of(testCase(1, "first", "OK"), testCase(2, "second", "OK")));
    }

    private void assertAccepted(String source, List<JudgeTestCase> cases) {
        assertThat(run(source, cases).outcome()).isEqualTo(SandboxOutcome.ACCEPTED);
    }

    @Test void rootFilesystemAndCompiledWorkspaceRejectWrites() {
        assertAccepted("""
                import java.nio.file.*;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        for (String target : new String[]{"/forgeoj-denied", "/etc/forgeoj-denied",
                                "/workspace/Main.java", "/workspace/Main.class", "/workspace/injected.class"}) {
                            try { Files.writeString(Path.of(target), "unauthorized"); throw new AssertionError("Write allowed"); }
                            catch (java.io.IOException denied) { }
                        }
                        System.out.println("OK");
                    }
                }
                """, List.of(testCase(1, "", "OK")));
    }

    @Test void sharedMemoryCannotProvideAnUncleanedWritableArea() {
        assertAccepted("""
                import java.nio.file.*;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        try { Files.writeString(Path.of("/dev/shm/forgeoj-marker"), "state");
                            throw new AssertionError("Additional shared-memory filesystem writable"); }
                        catch (java.io.IOException denied) { }
                        System.out.println("OK");
                    }
                }
                """, List.of(testCase(1, "", "OK")));
    }

    @Test void nonRootNoCapabilitiesNoNewPrivilegesAndSetuidAttemptAreEnforced() {
        assertAccepted("""
                import java.nio.file.*;
                import java.util.concurrent.TimeUnit;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        String status = Files.readString(Path.of("/proc/self/status"));
                        for (String line : new String[]{"Uid:\\t65532\\t65532\\t65532\\t65532",
                                "CapEff:\\t0000000000000000", "CapBnd:\\t0000000000000000", "NoNewPrivs:\\t1", "Seccomp:\\t2"}) {
                            if (!status.contains(line)) throw new AssertionError("Process restriction missing");
                        }
                        Process su = new ProcessBuilder("/usr/bin/su", "-s", "/bin/sh", "root", "-c", "id -u").start();
                        su.getOutputStream().close();
                        if (!su.waitFor(2, TimeUnit.SECONDS)) { su.destroyForcibly(); throw new AssertionError("Privilege probe hung"); }
                        if (su.exitValue() == 0) throw new AssertionError("Root switch succeeded");
                        if (Files.exists(Path.of("/var/run/docker.sock"))) throw new AssertionError("Docker socket exposed");
                        System.out.println("OK");
                    }
                }
                """, List.of(testCase(1, "", "OK")));
    }

    @Test void networkNamespaceHasOnlyLoopbackAndCannotReachAnExternalAddress() {
        assertAccepted("""
                import java.net.*;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        var interfaces = NetworkInterface.getNetworkInterfaces();
                        while (interfaces.hasMoreElements()) {
                            NetworkInterface value = interfaces.nextElement();
                            if (value.isUp() && !value.isLoopback()) throw new AssertionError("External interface exposed");
                        }
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress("192.0.2.1", 9), 500);
                            throw new AssertionError("Unexpected external connection");
                        } catch (java.io.IOException denied) { }
                        System.out.println("OK");
                    }
                }
                """, List.of(testCase(1, "", "OK")));
    }

    @Test void heapAllocationIsBoundedAndDoesNotExhaustTheWorkerHost() {
        assertAccepted("""
                import java.util.*;
                public class Main {
                    public static void main(String[] args) {
                        List<byte[]> retained = new ArrayList<>();
                        try {
                            for (int i = 0; i < 128; i++) retained.add(new byte[2 * 1024 * 1024]);
                            throw new AssertionError("Heap limit not enforced");
                        } catch (OutOfMemoryError bounded) { retained.clear(); System.out.println("BOUNDED"); }
                    }
                }
                """, List.of(testCase(1, "", "BOUNDED")));
    }

    @Test void processCreationHitsThePidLimitAndAllChildrenAreCleaned() {
        SandboxExecutionResult result = run("""
                import java.util.*;
                import java.nio.file.*;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        List<Process> children = new ArrayList<>();
                        boolean blocked = false;
                        try {
                            for (int i = 0; i < 80; i++) children.add(new ProcessBuilder("/usr/bin/sleep", "15").start());
                        } catch (java.io.IOException | OutOfMemoryError denied) { blocked = true; }
                        finally { for (Process child : children) child.destroyForcibly(); }
                        if (!blocked) throw new AssertionError("PID limit missing");
                        String events = Files.readString(Path.of("/sys/fs/cgroup/pids.events")).strip();
                        if (!events.matches("max [1-9][0-9]*")) throw new AssertionError("Kernel PID limit not reached");
                        System.out.println("BOUNDED");
                    }
                }
                """, List.of(testCase(1, "", "BOUNDED")));
        assertThat(result.outcome().name()).isEqualTo("SECURITY_VIOLATION");
        assertThat(lastExecution.exitCode()).isZero();
        assertThat(lastExecution.stdout()).contains("BOUNDED");
    }

    @Test void globalTmpFilesAndHostilePermissionsCannotLeakIntoTheNextCase() {
        assertAccepted("""
                import java.nio.file.*;
                import java.nio.file.attribute.PosixFilePermissions;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        String value = new String(System.in.readAllBytes()).strip();
                        if (value.equals("first")) {
                            Path nested = Files.createDirectories(Path.of("/tmp/owned/nested"));
                            Files.writeString(nested.resolve("marker"), "CASE_ONE_SECRET");
                            Files.createSymbolicLink(nested.resolve("source-link"), Path.of("/workspace/Main.java"));
                            Files.createSymbolicLink(Path.of("/tmp/source-link"), Path.of("/workspace/Main.java"));
                            Files.setPosixFilePermissions(nested, PosixFilePermissions.fromString("---------"));
                            Files.writeString(Path.of("/tmp/outside-case"), "CASE_ONE_SECRET");
                        } else {
                            for (String path : new String[]{"/tmp/owned", "/tmp/source-link", "/tmp/outside-case"}) {
                                if (Files.exists(Path.of(path), LinkOption.NOFOLLOW_LINKS)) throw new AssertionError("Temporary state survived");
                            }
                            if (Files.isWritable(Path.of("/workspace/Main.java"))) throw new AssertionError("Source permissions changed");
                        }
                        System.out.println("OK");
                    }
                }
                """, List.of(testCase(1, "first", "OK"), testCase(2, "second", "OK")));
    }

    @Test void timedOutProgramAndItsNonCooperatingDescendantsAreRemoved() {
        SandboxExecutionResult result = run("""
                public class Main {
                    public static void main(String[] args) throws Exception {
                        for (int i = 0; i < 8; i++) new ProcessBuilder("/usr/bin/sleep", "60").start();
                        while (true) Thread.onSpinWait();
                    }
                }
                """, List.of(testCase(1, "", "unused")));
        assertThat(result.outcome()).isEqualTo(SandboxOutcome.TIME_LIMIT_EXCEEDED);
        assertThat(lastExecution.timedOut()).isTrue();
        // run() also checks the exact task has no remaining container, including stopped ones.
    }

    @Test void tmpfsRejectsBoundedDiskExhaustionAndItsFilesAreCleaned() {
        assertAccepted("""
                import java.nio.file.*;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        byte[] block = new byte[1024 * 1024];
                        boolean bounded = false;
                        try (var file = Files.newOutputStream(Path.of(System.getProperty("java.io.tmpdir"), "fill"))) {
                            for (int i = 0; i < 32; i++) file.write(block);
                        } catch (java.io.IOException full) { bounded = true; }
                        if (!bounded) throw new AssertionError("tmpfs limit missing");
                        System.out.println("BOUNDED");
                    }
                }
                """, List.of(testCase(1, "", "BOUNDED")));
    }

    @Test void cgroupMemoryLimitKillsABoundedOversizedChildJvm() {
        SandboxExecutionResult result = run("""
                import java.nio.file.*;
                import java.util.concurrent.TimeUnit;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        if (args.length > 0) {
                            byte[] oversized = new byte[256 * 1024 * 1024];
                            for (int i = 0; i < oversized.length; i += 4096) oversized[i] = 1;
                            System.out.println(oversized[4096]);
                            return;
                        }
                        if (!Files.readString(Path.of("/sys/fs/cgroup/memory.max")).strip().equals("201326592"))
                            throw new AssertionError("Memory cgroup limit missing");
                        if (!Files.readString(Path.of("/sys/fs/cgroup/memory.swap.max")).strip().equals("0"))
                            throw new AssertionError("Swap not disabled");
                        Process child = new ProcessBuilder("java", "-XX:ActiveProcessorCount=1", "-Xmx512m",
                                "-cp", "/workspace", "Main", "oversized-child").start();
                        if (!child.waitFor(4, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("OOM probe hung"); }
                        if (child.exitValue() == 0) throw new AssertionError("Oversized child succeeded");
                        String events = Files.readString(Path.of("/sys/fs/cgroup/memory.events"));
                        if (!java.util.regex.Pattern.compile("(?m)^oom_kill [1-9][0-9]*$").matcher(events).find())
                            throw new AssertionError("Kernel OOM kill not observed");
                        System.out.println("BOUNDED");
                    }
                }
                """, List.of(testCase(1, "", "BOUNDED")));
        assertThat(result.outcome().name()).isEqualTo("MEMORY_LIMIT_EXCEEDED");
    }

    @Test void fabricatedResourceMessagesAndExitCodesRemainRuntimeErrors() {
        SandboxExecutionResult result = run("""
                import java.nio.file.*;
                public class Main {
                    public static void main(String[] args) {
                        for (String path : new String[]{"/sys/fs/cgroup/memory.events", "/sys/fs/cgroup/pids.events"}) {
                            try { Files.writeString(Path.of(path), "oom 9\\noom_kill 9\\nmax 9");
                                throw new AssertionError("Kernel evidence writable"); }
                            catch (java.io.IOException denied) { }
                        }
                        System.err.println("java.lang.OutOfMemoryError: Java heap space; oom 9; oom_kill 9; max 9; SECURITY_VIOLATION");
                        System.exit(137);
                    }
                }
                """, List.of(testCase(1, "", "unused")));
        assertThat(result.outcome()).isEqualTo(SandboxOutcome.RUNTIME_ERROR);
    }

    @Test void unavailableResourceEvidenceIsAPlatformFailureAndContainerIsStillCleaned() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run("""
                public class Main { public static void main(String[] args) { System.out.println("OK"); } }
                """, List.of(testCase(1, "", "OK")), true))
                .isInstanceOf(SandboxException.class).hasMessage("Could not read sandbox resource evidence");
    }

    @Test void anotherLiveSubmissionsSourceIsNotMountedOrReadable() {
        var runtime = new DockerCliSandboxRuntime(docker);
        var otherSnapshot = snapshot(UUID.randomUUID().toString(), "public class Main {}", List.of(testCase(1, "", "")));
        SandboxContainer other = runtime.prepare(otherSnapshot, UUID.randomUUID().toString());
        String privateMarker = "OTHER_SUBMISSION_" + UUID.randomUUID();
        try {
            assertThat(docker.execute(List.of("container", "start", other.identifier()), Duration.ofSeconds(15), 4096).exitCode()).isZero();
            assertThat(docker.execute(List.of("container", "exec", "-i", "--user", "65534:65534", other.identifier(),
                    "dd", "of=/workspace/other-source.java", "status=none"), privateMarker.getBytes(StandardCharsets.UTF_8),
                    Duration.ofSeconds(15), 4096).exitCode()).isZero();
            assertThat(docker.execute(List.of("container", "exec", other.identifier(), "cat", "/workspace/other-source.java"),
                    Duration.ofSeconds(15), 4096).stdout()).isEqualTo(privateMarker);
            assertAccepted("""
                    import java.nio.file.*;
                    public class Main {
                        public static void main(String[] args) {
                            for (String path : new String[]{"/workspace/other-source.java", "/var/run/docker.sock", "/host"}) {
                                if (Files.exists(Path.of(path))) throw new AssertionError("Cross-submission resource exposed");
                            }
                            System.out.println("OK");
                        }
                    }
                    """, List.of(testCase(1, "", "OK")));
        } finally { runtime.cleanup(other); }
    }

    @Test void hiddenAnswersAndFutureInputsAreNeverTransferredToTheProgram() {
        String secretAnswer = "EXPECTED_OUTPUT_" + UUID.randomUUID();
        String futureInput = "FUTURE_INPUT_" + UUID.randomUUID();
        String source = """
                import java.nio.file.*;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        try (var files = Files.list(Path.of("/workspace"))) {
                            if (!files.map(p -> p.getFileName().toString()).sorted().toList()
                                    .equals(java.util.List.of("Main.class", "Main.java"))) {
                                throw new AssertionError("Extra judge files exposed");
                            }
                        }
                        System.out.println("NOT_THE_ANSWER");
                    }
                }
                """;
        assertThat(run(source, List.of(testCase(1, "FIRST_INPUT", secretAnswer), testCase(2, futureInput, "unused")))
                .outcome()).isEqualTo(SandboxOutcome.WRONG_ANSWER);
        assertThat(transferredInput).doesNotContain(secretAnswer, futureInput);
    }

    private SandboxExecutionResult run(String source, List<JudgeTestCase> cases) {
        return run(source, cases, false);
    }

    private SandboxExecutionResult run(String source, List<JudgeTestCase> cases, boolean failEvidenceRead) {
        String taskId = UUID.randomUUID().toString();
        JudgeTaskSnapshot snapshot = snapshot(taskId, source, cases);
        RecordingExecutor recording = new RecordingExecutor(docker);
        recording.failEvidenceRead = failEvidenceRead;
        try {
            SandboxExecutionResult result = new DockerCliSandboxRuntime(recording).execute(snapshot, UUID.randomUUID().toString());
            lastExecution = recording.lastExecution;
            transferredInput = recording.inputs.toString(StandardCharsets.UTF_8);
            return result;
        } finally {
            DockerCommandResult remaining = docker.execute(List.of("container", "ls", "--all", "--quiet", "--no-trunc",
                    "--filter", "label=com.forgeoj.task-id=" + taskId), Duration.ofSeconds(15), 4096);
            assertThat(remaining.exitCode()).isZero();
            assertThat(remaining.stdout()).isBlank();
            assertThat(recording.commands).allSatisfy(command -> assertThat(command).doesNotContain(source));
        }
    }

    private JudgeTaskSnapshot snapshot(String taskId, String source, List<JudgeTestCase> cases) {
        return new JudgeTaskSnapshot(taskId, UUID.randomUUID().toString(), 1,
                "JAVA_21", source, "a".repeat(64), 5000, 192, 65536,
                "trim-trailing-whitespace-v1", "m0-v1", IMAGE, "b".repeat(64), cases);
    }

    private static JudgeTestCase testCase(int ordinal, String input, String expected) {
        return new JudgeTestCase(ordinal, input.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static final class RecordingExecutor implements DockerCommandExecutor {
        private final DockerCommandExecutor delegate;
        private final List<List<String>> commands = new ArrayList<>();
        private final java.io.ByteArrayOutputStream inputs = new java.io.ByteArrayOutputStream();
        private DockerCommandResult lastExecution;
        private boolean failEvidenceRead;
        RecordingExecutor(DockerCommandExecutor delegate) { this.delegate = delegate; }
        @Override public DockerCommandResult execute(List<String> command, Duration timeout, int limit) {
            return execute(command, new byte[0], timeout, limit);
        }
        @Override public DockerCommandResult execute(List<String> command, byte[] input, Duration timeout, int limit) {
            commands.add(List.copyOf(command));
            inputs.writeBytes(input);
            if (failEvidenceRead && command.contains("/sys/fs/cgroup/memory.events")) {
                return new DockerCommandResult(1, "", "PRIVATE_CONTROL_ERROR", false, false);
            }
            DockerCommandResult result = delegate.execute(command, input, timeout, limit);
            if (command.contains("java")) lastExecution = result;
            return result;
        }
    }
}
