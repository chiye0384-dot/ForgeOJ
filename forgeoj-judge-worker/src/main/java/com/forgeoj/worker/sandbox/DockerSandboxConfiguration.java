package com.forgeoj.worker.sandbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "forgeoj.worker.sandbox.enabled", havingValue = "true")
class DockerSandboxConfiguration {

    @Bean
    DockerCommandExecutor dockerCommandExecutor(
            @Value("${forgeoj.worker.docker.executable:docker}") String executable) {
        return new ProcessBuilderDockerCommandExecutor(executable);
    }

    @Bean
    SandboxRuntime sandboxRuntime(DockerCommandExecutor executor, SandboxAttemptLookup attempts) {
        return new DockerCliSandboxRuntime(executor, attempts);
    }

    @Bean
    ApplicationRunner dockerSandboxStartup(SandboxRuntime runtime) {
        return arguments -> {
            runtime.verifyAvailable();
            runtime.cleanupManagedContainers();
        };
    }
}
