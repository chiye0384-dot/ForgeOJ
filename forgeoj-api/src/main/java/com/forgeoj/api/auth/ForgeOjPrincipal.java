package com.forgeoj.api.auth;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class ForgeOjPrincipal implements UserDetails {

    private final long userId;
    private final String username;
    private final String passwordHash;
    private final boolean enabled;
    private final String sessionId;

    public ForgeOjPrincipal(long userId, String username, String passwordHash, boolean enabled) {
        this(userId, username, passwordHash, enabled, null);
    }

    public ForgeOjPrincipal(long userId, String username, String passwordHash, boolean enabled, String sessionId) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.sessionId = sessionId;
    }

    public String sessionId() { return sessionId; }

    public long userId() {
        return userId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
