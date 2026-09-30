package com.forgeoj.worker.task;

public final class LeaseOwnershipLostException extends RuntimeException {

    LeaseOwnershipLostException(String message) {
        super(message);
    }
}
