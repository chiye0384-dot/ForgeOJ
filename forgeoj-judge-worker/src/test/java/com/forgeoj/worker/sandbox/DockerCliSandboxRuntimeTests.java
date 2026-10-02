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
    private static final String ATTEMPT_ID = "c5862430-ab78-436f-b78c-bcdeb728503a";
    private static final String CONTAINER_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String NAME = "forgeoj-b844c17394364d35a45ca6f5041f7d20-c5862430ab78436fb78cbcdeb728503a";
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
        SandboxContainer container = runtime.prepare(snapshot(), ATTEMPT_ID);

        assertThat(container.name()).isEqualTo(NAME);
        assertThat(container.identifier()).isEqualTo(CONTAINER_ID);
        assertThat(executor.commands().getFirst())
                .containsExactly("version", "--format", "{{.Server.Os}}");
        assertThat(executor.commands().get(1))
                .containsSubsequence("container", "create")
                .contains("--init")
                .containsSubsequence("--ipc", "none")
                .contains("com.forgeoj.attempt-id=" + ATTEMPT_ID)
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

        assertThatThrownBy(() -> runtime.prepare(unsafe, ATTEMPT_ID))
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
                                                "unknown-comparison-rule"), ATTEMPT_ID))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("comparison");
        assertThatThrownBy(
                        () ->
                                runtime.prepare(
                                        snapshot(
                                                IMAGE,
                                                "JAVA_17",
                                                "trim-trailing-whitespace-v1"), ATTEMPT_ID))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("language");
        assertThat(executor.commands()).isEmpty();
    }

    @Test
    void cleanupVerifiesOwnershipAndUsesImmutableContainerIdentifier() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.enqueue(success(metadata()));
        executor.enqueue(success("removed\n"));
        DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(executor);

        runtime.cleanup(
                new SandboxContainer(TASK_ID, ATTEMPT_ID, NAME, CONTAINER_ID));

        assertThat(executor.commands())
                .containsExactly(
                        List.of("container", "inspect", "--format", "{{.Id}}|{{.Name}}|{{json .Config.Labels}}", CONTAINER_ID),
                        List.of(
                                "container",
                                "rm",
                                "--force",
                                "--volumes",
                                CONTAINER_ID));
    }

    @Test void sweepPreservesRunningOrUnknownAttempts() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.enqueue(success(CONTAINER_ID));
        executor.enqueue(success(metadata()));
        new DockerCliSandboxRuntime(executor, (task, attempt) -> false).cleanupManagedContainers();
        assertThat(executor.commands()).hasSize(2).noneMatch(command -> command.contains("rm"));
    }

    @Test void sweepRemovesOnlyAnExactlyIdentifiedClosedAttempt() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.enqueue(success(CONTAINER_ID));
        executor.enqueue(success(metadata()));
        executor.enqueue(success("removed"));
        new DockerCliSandboxRuntime(executor, (task, attempt) -> task.equals(TASK_ID) && attempt.equals(ATTEMPT_ID))
                .cleanupManagedContainers();
        assertThat(executor.commands().getLast()).containsExactly("container", "rm", "--force", "--volumes", CONTAINER_ID);
    }

    @Test void sweepDoesNotRemoveLegacyOrMismatchedLabels() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.enqueue(success(CONTAINER_ID));
        executor.enqueue(success(metadata().replace("com.forgeoj.attempt-id", "legacy-attempt")));
        new DockerCliSandboxRuntime(executor, (task, attempt) -> { throw new AssertionError("Invalid metadata reached database"); })
                .cleanupManagedContainers();
        assertThat(executor.commands()).hasSize(2).noneMatch(command -> command.contains("rm"));
    }

    @Test void cleanupRejectsWrongAttemptNameAndShortIdentifierBeforeDocker() {
        RecordingExecutor executor = new RecordingExecutor();
        DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(executor);
        assertThatThrownBy(() -> runtime.cleanup(new SandboxContainer(TASK_ID, ATTEMPT_ID, "foreign", CONTAINER_ID)))
                .isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> runtime.cleanup(new SandboxContainer(TASK_ID, ATTEMPT_ID, NAME, "0123456")))
                .isInstanceOf(SandboxException.class);
        assertThat(executor.commands()).isEmpty();
    }

    @Test void cleanupIsIdempotentWhenAnotherWorkerAlreadyRemovedTheSameIdentifier() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.enqueue(new DockerCommandResult(1, "", "", false, false));
        executor.enqueue(success(""));
        executor.enqueue(success(""));
        new DockerCliSandboxRuntime(executor).cleanup(new SandboxContainer(TASK_ID, ATTEMPT_ID, NAME, CONTAINER_ID));
        assertThat(executor.commands()).noneMatch(command -> command.contains("rm"));
    }

    @Test void databaseFailureNeverAuthorizesRemoval() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.enqueue(success(CONTAINER_ID));
        executor.enqueue(success(metadata()));
        var runtime = new DockerCliSandboxRuntime(executor, (task, attempt) -> { throw new IllegalStateException("DB unavailable"); });
        assertThatThrownBy(runtime::cleanupManagedContainers).isInstanceOf(IllegalStateException.class);
        assertThat(executor.commands()).noneMatch(command -> command.contains("rm"));
    }

    @Test void sameTaskRetriesHaveDifferentNamesAndOldCleanupCannotTargetTheNewName() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.enqueue(success(CONTAINER_ID));
        executor.enqueue(success("a".repeat(64)));
        DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(executor);
        SandboxContainer first = runtime.prepare(snapshot(), ATTEMPT_ID);
        SandboxContainer next = runtime.prepare(snapshot(), "00000000-0000-4000-8000-000000000001");
        assertThat(first.name()).isNotEqualTo(next.name());
        executor.enqueue(success(metadata()));
        executor.enqueue(success("removed"));
        runtime.cleanup(first);
        assertThat(executor.commands().getLast()).contains(CONTAINER_ID).doesNotContain(next.name(), next.identifier());
    }

    @Test void sweepRejectsAnInspectIdentityMismatchWithoutConsultingDatabase() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.enqueue(success(CONTAINER_ID));
        executor.enqueue(success(metadata().replace("|/" + NAME, "|/foreign-name")));
        new DockerCliSandboxRuntime(executor, (task, attempt) -> { throw new AssertionError("Foreign metadata reached database"); })
                .cleanupManagedContainers();
        assertThat(executor.commands()).hasSize(2).noneMatch(command -> command.contains("rm"));
    }

    @Test void concurrentSweepRemovalIsSuccessfulOnlyAfterExactIdentifierIsAbsent() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.enqueue(success(CONTAINER_ID));
        executor.enqueue(success(metadata()));
        executor.enqueue(new DockerCommandResult(1, "", "", false, false));
        executor.enqueue(success(""));
        new DockerCliSandboxRuntime(executor, (task, attempt) -> true).cleanupManagedContainers();
        assertThat(executor.commands().getLast()).containsExactly("container", "ls", "--all", "--quiet", "--no-trunc",
                "--filter", "id=" + CONTAINER_ID);
    }

    private String metadata() {
        return CONTAINER_ID + "|/" + NAME + "|{\"com.forgeoj.managed\":\"true\",\"com.forgeoj.task-id\":\""
                + TASK_ID + "\",\"com.forgeoj.attempt-id\":\"" + ATTEMPT_ID + "\"}";
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
