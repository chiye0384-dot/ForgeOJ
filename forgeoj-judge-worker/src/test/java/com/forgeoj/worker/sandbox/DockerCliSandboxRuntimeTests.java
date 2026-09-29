package com.forgeoj.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeoj.worker.snapshot.JudgeTaskSnapshot;
import com.forgeoj.worker.snapshot.JudgeTestCase;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class DockerCliSandboxRuntimeTests {

    private static final String TASK_ID = "b844c173-9436-4d35-a45c-a6f5041f7d20";
    private static final String SUBMISSION_ID = "a9987de8-880d-42af-b463-06ae4f7b9717";
    private static final String IMAGE =
            "eclipse-temurin:21.0.12_8-jdk-jammy"
                    + "@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438";
    private static final String SOURCE_SENTINEL = "PRIVATE_SOURCE_SENTINEL";
    private static final String HIDDEN_SENTINEL = "HIDDEN_TEST_SENTINEL";

    @Test
    void createsContainerWithServerControlledRestrictionsAndNoPrivatePayloadArguments() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.enqueue(success("linux\n"));
        executor.enqueue(success("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n"));
        DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(executor);

        runtime.verifyAvailable();
        SandboxContainer container = runtime.prepare(snapshot());

        assertThat(container.name()).isEqualTo("forgeoj-b844c17394364d35a45ca6f5041f7d20");
        assertThat(executor.commands().getFirst())
                .containsExactly("version", "--format", "{{.Server.Os}}");
        assertThat(executor.commands().get(1))
                .containsSubsequence("container", "create")
                .contains("--network", "none")
                .contains("--read-only")
                .contains("--cap-drop", "ALL")
                .contains("--security-opt", "no-new-privileges")
                .contains("--user", "65534:65534")
                .contains("--memory", "192m")
                .contains("--memory-swap", "192m")
                .contains("--pids-limit", "64")
                .contains("--tmpfs", "/workspace:rw,noexec,nosuid,nodev,size=64m,mode=1777")
                .contains("--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=16m")
                .contains("--workdir", "/workspace")
                .contains(IMAGE)
                .noneMatch(
                        argument ->
                                argument.contains(SOURCE_SENTINEL)
                                        || argument.contains(HIDDEN_SENTINEL)
                                        || argument.contains(SUBMISSION_ID));
    }

    @Test
    void rejectsUnpinnedImageBeforeCallingDocker() {
        RecordingExecutor executor = new RecordingExecutor();
        DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(executor);
        JudgeTaskSnapshot unsafe = snapshot("eclipse-temurin:21-jdk");

        assertThatThrownBy(() -> runtime.prepare(unsafe))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("digest");
        assertThat(executor.commands()).isEmpty();
    }

    @Test
    void rejectsUnsupportedVersionedExecutionRulesBeforeCallingDocker() {
        RecordingExecutor executor = new RecordingExecutor();
        DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(executor);

        assertThatThrownBy(
                        () ->
                                runtime.prepare(
                                        snapshot(
                                                IMAGE,
                                                "JAVA_21",
                                                "unknown-comparison-rule")))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("comparison");
        assertThatThrownBy(
                        () ->
                                runtime.prepare(
                                        snapshot(
                                                IMAGE,
                                                "JAVA_17",
                                                "trim-trailing-whitespace-v1")))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("language");
        assertThat(executor.commands()).isEmpty();
    }

    @Test
    void cleanupUsesOnlyServerGeneratedContainerName() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.enqueue(success("removed\n"));
        DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(executor);

        runtime.cleanup(
                new SandboxContainer(TASK_ID, "forgeoj-b844c17394364d35a45ca6f5041f7d20"));

        assertThat(executor.commands())
                .containsExactly(
                        List.of(
                                "container",
                                "rm",
                                "--force",
                                "--volumes",
                                "forgeoj-b844c17394364d35a45ca6f5041f7d20"));
    }

    private JudgeTaskSnapshot snapshot() {
        return snapshot(IMAGE);
    }

    private JudgeTaskSnapshot snapshot(String image) {
        return snapshot(image, "JAVA_21", "trim-trailing-whitespace-v1");
    }

    private JudgeTaskSnapshot snapshot(String image, String language, String comparisonRuleVersion) {
        return new JudgeTaskSnapshot(
                TASK_ID,
                SUBMISSION_ID,
                1L,
                language,
                "public class Main { String marker = \"" + SOURCE_SENTINEL + "\"; }",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                1500,
                192,
                65536,
                comparisonRuleVersion,
                "m0-v1",
                image,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                List.of(
                        new JudgeTestCase(
                                1,
                                HIDDEN_SENTINEL.getBytes(StandardCharsets.UTF_8),
                                "expected".getBytes(StandardCharsets.UTF_8))));
    }

    private DockerCommandResult success(String stdout) {
        return new DockerCommandResult(0, stdout, "", false, false);
    }

    private static final class RecordingExecutor implements DockerCommandExecutor {

        private final List<List<String>> commands = new ArrayList<>();
        private final List<DockerCommandResult> results = new ArrayList<>();

        @Override
        public DockerCommandResult execute(
                List<String> arguments, Duration timeout, int outputLimitBytes) {
            commands.add(List.copyOf(arguments));
            return results.removeFirst();
        }

        void enqueue(DockerCommandResult result) {
            results.add(result);
        }

        List<List<String>> commands() {
            return commands;
        }
    }
}
