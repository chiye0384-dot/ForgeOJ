package com.forgeoj.worker.sandbox;

public final class SandboxException extends RuntimeException {

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
