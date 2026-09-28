package com.forgeoj.api.auth;

import com.forgeoj.api.user.UserAccountCredentials;
import com.forgeoj.api.user.UserAccountMapper;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserAccountMapper userAccountMapper;

    public DatabaseUserDetailsService(UserAccountMapper userAccountMapper) {
        this.userAccountMapper = userAccountMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccountCredentials account =
                userAccountMapper
                        .findByUsername(username)
                        .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));

        return new ForgeOjPrincipal(
                account.id(), account.username(), account.passwordHash(), account.isActive());
    }
}
