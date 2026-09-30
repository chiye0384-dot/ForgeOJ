package com.forgeoj.worker.sandbox;

public final class InvalidSandboxConfigurationException extends SandboxException {

    InvalidSandboxConfigurationException(String message) {
        super(message);
    }

    InvalidSandboxConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
