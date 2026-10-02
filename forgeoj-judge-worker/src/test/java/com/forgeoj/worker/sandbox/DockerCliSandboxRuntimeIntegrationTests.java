package com.forgeoj.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeoj.worker.snapshot.JudgeTaskSnapshot;
import com.forgeoj.worker.snapshot.JudgeTestCase;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DockerCliSandboxRuntimeIntegrationTests {

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
        assertThat(pulled.timedOut()).isFalse();
        assertThat(pulled.outputTruncated()).isFalse();
    }

    @Test
    void realDockerCreatesRestrictedContainerAndCleanupRemovesIt() {
        DockerCommandExecutor executor = new ProcessBuilderDockerCommandExecutor("docker");
        DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(executor);
        runtime.verifyAvailable();
        SandboxContainer container = runtime.prepare(snapshot(), UUID.randomUUID().toString());

        try {
            DockerCommandResult inspected =
                    executor.execute(
                            List.of(
                                    "container",
                                    "inspect",
                                    "--format",
                                    "{{.HostConfig.NetworkMode}}|{{.HostConfig.ReadonlyRootfs}}|"
                                            + "{{.Config.User}}|{{.HostConfig.Memory}}|"
                                            + "{{.HostConfig.MemorySwap}}|{{.HostConfig.PidsLimit}}|"
                                            + "{{json .HostConfig.CapDrop}}|"
                                            + "{{json .HostConfig.SecurityOpt}}|"
                                            + "{{json .HostConfig.Tmpfs}}|{{.HostConfig.IpcMode}}|{{.HostConfig.Init}}",
                                    container.name()),
                            Duration.ofSeconds(15),
                            64 * 1024);

            assertThat(inspected.exitCode()).isZero();
            assertThat(inspected.timedOut()).isFalse();
            assertThat(inspected.outputTruncated()).isFalse();
            assertThat(inspected.stdout())
                    .contains("none|true|65534:65534|201326592|201326592|64")
                    .contains("[\"ALL\"]")
                    .contains("[\"no-new-privileges\"]")
                    .contains("/workspace")
                    .contains("/tmp")
                    .endsWith("|none|true\n");
        } finally {
            runtime.cleanup(container);
        }

        DockerCommandResult absent =
                executor.execute(
                        List.of(
                                "container",
                                "inspect",
                                "--format",
                                "{{.Id}}",
                                container.name()),
                        Duration.ofSeconds(15),
                        64 * 1024);
        assertThat(absent.exitCode()).isNotZero();
    }

    private JudgeTaskSnapshot snapshot() {
        return new JudgeTaskSnapshot(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                1L,
                "JAVA_21",
                "public class Main {}",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                1500,
                192,
                65536,
                "trim-trailing-whitespace-v1",
                "m0-v1",
                IMAGE,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                List.of(new JudgeTestCase(1, new byte[] {1}, new byte[] {2})));
    }
}
