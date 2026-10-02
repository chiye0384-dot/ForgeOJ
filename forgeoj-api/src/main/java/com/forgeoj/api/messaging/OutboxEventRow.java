package com.forgeoj.api.messaging;

record OutboxEventRow(
        String id, String eventType, int sequenceNo, String payload, int publishAttempts,
        String judgeTaskId, String submissionId) {}
