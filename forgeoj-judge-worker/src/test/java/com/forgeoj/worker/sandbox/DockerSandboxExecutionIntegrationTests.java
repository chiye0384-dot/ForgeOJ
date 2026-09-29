package com.forgeoj.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeoj.worker.snapshot.JudgeTaskSnapshot;
import com.forgeoj.worker.snapshot.JudgeTestCase;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DockerSandboxExecutionIntegrationTests {

    private static final String IMAGE =
            "eclipse-temurin:21.0.12_8-jdk-jammy"
                    + "@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438";

    @BeforeAll
    static void ensurePinnedImageIsPresent() {
        DockerCommandExecutor executor = new ProcessBuilderDockerCommandExecutor("docker");
        DockerCommandResult inspected =
                executor.execute(
                        List.of("image", "inspect", IMAGE, "--format", "{{.Id}}"),
                        Duration.ofSeconds(15),
                        64 * 1024);
        if (inspected.exitCode() == 0) {
            return;
        }
        DockerCommandResult pulled =
                executor.execute(
                        List.of("pull", IMAGE), Duration.ofMinutes(3), 1024 * 1024);
        assertThat(pulled.exitCode()).isZero();
    }

    @Test
    void executesM0UserOutcomesWithoutPayloadArgumentsAndCleansEveryContainer() {
        RecordingExecutor executor =
                new RecordingExecutor(new ProcessBuilderDockerCommandExecutor("docker"));
        DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(executor);

        String acceptedSource = sumSource();
        assertThat(runtime.execute(snapshot(acceptedSource, 2000, 65536, sumCases())).outcome())
                .isEqualTo(SandboxOutcome.ACCEPTED);
        assertThat(executor.commands().stream().filter(this::isJavacCommand)).hasSize(1);
        assertThat(executor.commands().stream().filter(this::isJavaCommand)).hasSize(2);
        assertThat(executor.commands())
                .allSatisfy(
                        command ->
                                assertThat(command)
                                        .noneMatch(
                                                argument ->
                                                        argument.contains(acceptedSource)
                                                                || argument.contains("1 2")
                                                                || argument.contains("-4 9")));
        executor.clear();
        assertThat(runtime.execute(snapshot("public class Main { public static void main(String[] args) { System.out.println(0); } }", 2000, 65536, sumCases())).outcome())
                .isEqualTo(SandboxOutcome.WRONG_ANSWER);
        assertThat(executor.commands().stream().filter(this::isJavaCommand)).hasSize(1);
        executor.clear();
        assertThat(runtime.execute(snapshot("public class Main { broken }", 2000, 65536, oneCase())).outcome())
                .isEqualTo(SandboxOutcome.COMPILE_ERROR);
        assertThat(runtime.execute(snapshot("public class Main { public static void main(String[] args) { throw new RuntimeException(); } }", 2000, 65536, oneCase())).outcome())
                .isEqualTo(SandboxOutcome.RUNTIME_ERROR);
        assertThat(runtime.execute(snapshot("public class Main { public static void main(String[] args) { while (true) { } } }", 500, 65536, oneCase())).outcome())
                .isEqualTo(SandboxOutcome.TIME_LIMIT_EXCEEDED);
        assertThat(runtime.execute(snapshot("public class Main { public static void main(String[] args) { while (true) { System.out.print(\"0123456789\"); } } }", 2000, 1024, oneCase())).outcome())
                .isEqualTo(SandboxOutcome.OUTPUT_LIMIT_EXCEEDED);

        DockerCommandResult managed =
                executor.execute(
                        List.of(
                                "container",
                                "ls",
                                "--all",
                                "--quiet",
                                "--filter",
                                "label=com.forgeoj.managed=true"),
                        Duration.ofSeconds(15),
                        64 * 1024);
        assertThat(managed.exitCode()).isZero();
        assertThat(managed.stdout()).isBlank();
    }

    private boolean isJavacCommand(List<String> command) {
        return command.contains("javac");
    }

    private boolean isJavaCommand(List<String> command) {
        return command.contains("java");
    }

    private JudgeTaskSnapshot snapshot(
            String source, int timeLimitMs, long outputLimitBytes, List<JudgeTestCase> cases) {
        return new JudgeTaskSnapshot(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                1L,
                "JAVA_21",
                source,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                timeLimitMs,
                192,
                outputLimitBytes,
                "trim-trailing-whitespace-v1",
                "m0-v1",
                IMAGE,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                cases);
    }

    private List<JudgeTestCase> sumCases() {
        return List.of(testCase(1, "1 2\n", "3\n"), testCase(2, "-4 9\n", "5\n"));
    }

    private List<JudgeTestCase> oneCase() {
        return List.of(testCase(1, "", ""));
    }

    private JudgeTestCase testCase(int ordinal, String input, String expected) {
        return new JudgeTestCase(
                ordinal,
                input.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private String sumSource() {
        return """
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) {
                        Scanner scanner = new Scanner(System.in);
                        System.out.println(scanner.nextLong() + scanner.nextLong());
                    }
                }
                """;
    }

    private static final class RecordingExecutor implements DockerCommandExecutor {

        private final DockerCommandExecutor delegate;
        private final java.util.ArrayList<List<String>> commands = new java.util.ArrayList<>();

        private RecordingExecutor(DockerCommandExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public DockerCommandResult execute(
                List<String> arguments, Duration timeout, int outputLimitBytes) {
            commands.add(List.copyOf(arguments));
            return delegate.execute(arguments, timeout, outputLimitBytes);
        }

        @Override
        public DockerCommandResult execute(
                List<String> arguments,
                byte[] standardInput,
                Duration timeout,
                int outputLimitBytes) {
            commands.add(List.copyOf(arguments));
            return delegate.execute(arguments, standardInput, timeout, outputLimitBytes);
        }

        List<List<String>> commands() {
            return List.copyOf(commands);
        }

        void clear() {
            commands.clear();
        }
    }
}
