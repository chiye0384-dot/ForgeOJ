package com.forgeoj.worker.messaging;

final class InvalidJudgeTaskMessageException extends RuntimeException {

    InvalidJudgeTaskMessageException(Throwable cause) {
        super(cause);
    }

    InvalidJudgeTaskMessageException(String message) {
        super(message);
    }
}
