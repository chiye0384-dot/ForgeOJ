package com.forgeoj.api.user;

public record UserAccountCredentials(
        long id, String username, String passwordHash, String status) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
