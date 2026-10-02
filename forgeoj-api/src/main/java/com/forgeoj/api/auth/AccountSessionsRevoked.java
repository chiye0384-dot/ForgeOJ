package com.forgeoj.api.auth;

/** Internal committed invalidation. A null sessionId means all sessions of the user. */
public record AccountSessionsRevoked(long userId, String sessionId) {}
