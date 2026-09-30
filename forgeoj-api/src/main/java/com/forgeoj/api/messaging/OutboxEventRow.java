package com.forgeoj.api.messaging;

record OutboxEventRow(String id, String eventType, String payload) {}
