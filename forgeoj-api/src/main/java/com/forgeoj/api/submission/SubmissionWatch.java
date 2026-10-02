package com.forgeoj.api.submission;

import java.util.function.BooleanSupplier;

// Server-side only; never serialize the session or authentication into a notification.
record SubmissionWatch(long userId, String submissionId, String sessionId, BooleanSupplier authenticated) {
    static final String ATTRIBUTE = SubmissionWatch.class.getName();

    boolean stillAuthenticated() {
        return authenticated.getAsBoolean();
    }
}
