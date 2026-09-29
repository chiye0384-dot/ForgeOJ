package com.forgeoj.worker.snapshot;

public final class JudgeTaskSnapshotException extends RuntimeException {

    public JudgeTaskSnapshotException(String message) {
        super(message);
    }

    public JudgeTaskSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
