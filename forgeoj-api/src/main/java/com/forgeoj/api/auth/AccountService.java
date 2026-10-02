package com.forgeoj.api.auth;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {

    public record Login(
            long userId,
            String username,
            String sessionId,
            String refresh,
            LocalDateTime expiresAt) {}

    private record Delivery(String email, String purpose, String token) {}

    private final AccountMapper mapper;
    private final PasswordEncoder passwords;
    private final AccountMailDelivery mail;
    private final TransactionTemplate transaction;
    private final String dummyHash;
    private final ApplicationEventPublisher events;

    public AccountService(
            AccountMapper mapper,
            PasswordEncoder passwords,
            AccountMailDelivery mail,
            PlatformTransactionManager manager,
            ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.passwords = passwords;
        this.mail = mail;
        transaction = new TransactionTemplate(manager);
        this.events = events;
        dummyHash = passwords.encode("not-a-real-account-secret");
    }

    public void register(String username, String email, String password, String nickname) {
        String name = AccountInput.username(username),
                address = AccountInput.email(email),
                display = AccountInput.nickname(nickname);
        String hash = passwords.encode(AccountInput.password(password));
        Delivery delivery;
        try {
            delivery = transaction.execute(status -> {
                mapper.insertAccount(name, address, hash, display);
                long id = mapper.insertedId();
                mapper.insertQuota(id);
                return issue(id, "ACTIVATE", address, 24 * 60);
            });
        } catch (DuplicateKeyException conflict) {
            return;
        }
        deliver(delivery);
    }

    public Login login(String identifier, String password) {
        if (identifier == null
                || identifier.isBlank()
                || identifier.length() > 254
                || password == null
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            unauthorized();
        }
        String key = identifier.strip();
        if (key.contains("@")) {
            try {
                key = AccountInput.email(key);
            } catch (ResponseStatusException invalid) {
                unauthorized();
            }
        }
        var found = mapper.findAccount(key);
        if (!passwords.matches(
                password, found.map(AccountMapper.Account::passwordHash).orElse(dummyHash))) {
            unauthorized();
        }
        var candidate = found.orElseThrow(AccountService::authenticationFailure);
        return transaction.execute(status -> {
            var account =
                    mapper.lockAccount(candidate.id()).orElseThrow(AccountService::authenticationFailure);
            if (!"ACTIVE".equals(account.status())
                    || !account.passwordHash().equals(candidate.passwordHash())) {
                unauthorized();
            }
            var expires = now().plusDays(7);
            String sid = UUID.randomUUID().toString(), refresh = AccountSecrets.token();
            mapper.insertSession(new AccountMapper.Session(sid, account.id(), expires, null));
            mapper.insertRefresh(AccountSecrets.digest(refresh), sid);
            return new Login(account.id(), account.username(), sid, refresh, expires);
        });
    }

    public Login refresh(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            unauthorized();
        }
        String hash = AccountSecrets.digest(token);
        var first = mapper.refresh(hash).orElseThrow(AccountService::authenticationFailure);
        var firstSession =
                mapper.session(first.sessionId()).orElseThrow(AccountService::authenticationFailure);
        Login result = transaction.execute(status -> {
            var account =
                    mapper.lockAccount(firstSession.userId()).orElseThrow(AccountService::authenticationFailure);
            var session =
                    mapper.lockSession(first.sessionId()).orElseThrow(AccountService::authenticationFailure);
            var refresh = mapper.lockRefresh(hash).orElseThrow(AccountService::authenticationFailure);
            if (refresh.consumedAt() != null) {
                mapper.revoke(session.id());
                revoked(account.id(), session.id());
                return null;
            } // Commit revocation before returning 401.
            if (!"ACTIVE".equals(account.status())
                    || session.revokedAt() != null
                    || !session.expiresAt().isAfter(now())) {
                unauthorized();
            }
            if (mapper.consumeRefresh(hash) != 1) {
                throw new IllegalStateException("Refresh consumption failed");
            }
            String next = AccountSecrets.token();
            mapper.insertRefresh(AccountSecrets.digest(next), session.id());
            return new Login(account.id(), account.username(), session.id(), next, session.expiresAt());
        });
        if (result == null) {
            unauthorized();
        }
        return result;
    }

    public boolean authenticated(long userId, String sid) {
        return sid != null && mapper.authenticated(sid).filter(a -> a.id() == userId).isPresent();
    }

    public void requireActive(long userId, String sid) {
        if (!authenticated(userId, sid)) {
            unauthorized();
        }
    }

    public void requireCurrentWrite(long userId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || !(auth.getPrincipal() instanceof ForgeOjPrincipal p)
                || p.userId() != userId
                || p.sessionId() == null) {
            unauthorized();
        }
        var principal = (ForgeOjPrincipal) auth.getPrincipal();
        lockAuthenticated(userId, principal.sessionId());
    }

    public void logout(String refresh) {
        if (refresh == null || !refresh.matches("[A-Za-z0-9_-]{43}")) {
            return;
        }
        var token = mapper.refresh(AccountSecrets.digest(refresh));
        if (token.isEmpty()) {
            return;
        }
        var session = mapper.session(token.get().sessionId());
        if (session.isEmpty()) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            mapper.lockAccount(session.get().userId());
            mapper.lockSession(session.get().id());
            mapper.revoke(session.get().id());
            revoked(session.get().userId(), session.get().id());
        });
    }

    public void logoutCurrent(long id, String sid) {
        transaction.executeWithoutResult(status -> {
            mapper.lockAccount(id);
            var session = mapper.lockSession(sid);
            if (session.isPresent() && session.get().userId() == id) {
                mapper.revoke(sid);
                revoked(id, sid);
            }
        });
    }

    private void revoked(long id, String sid) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                events.publishEvent(new AccountSessionsRevoked(id, sid));
            }
        });
    }

    public void logoutAll(long id, String sid) {
        transaction.executeWithoutResult(status -> {
            lockAuthenticated(id, sid);
            mapper.revokeAll(id);
            revoked(id, null);
        });
    }

    public void changePassword(long id, String sid, String current, String next) {
        String hash = passwords.encode(AccountInput.password(next));
        transaction.executeWithoutResult(status -> {
            var account = lockAuthenticated(id, sid);
            if (current == null || !passwords.matches(current, account.passwordHash())) {
                unauthorized();
            }
            mapper.password(id, hash);
            mapper.revokeAll(id);
            revoked(id, null);
            mapper.invalidateActions(id, "RESET_PASSWORD");
            mapper.invalidateActions(id, "BIND_EMAIL");
        });
    }

    public void disable(long id) {
        transaction.executeWithoutResult(status -> {
            mapper.lockAccount(id).orElseThrow(AccountService::authenticationFailure);
            mapper.disable(id);
            mapper.revokeAll(id);
            revoked(id, null);
            for (String purpose : new String[] {"ACTIVATE", "RESET_PASSWORD", "BIND_EMAIL"}) {
                mapper.invalidateActions(id, purpose);
            }
        });
    }

    public void request(String email, String purpose) {
        String address = AccountInput.email(email);
        var found = mapper.findAccount(address);
        if (found.isEmpty()) {
            return;
        }
        var delivery = transaction.execute(status -> {
            var account =
                    mapper.lockAccount(found.get().id()).orElseThrow(AccountService::authenticationFailure);
            if (!address.equals(account.email())
                    || ("ACTIVATE".equals(purpose)
                            ? !"PENDING_VERIFICATION".equals(account.status())
                            : !"ACTIVE".equals(account.status()) || account.emailVerifiedAt() == null)) {
                return null;
            }
            return issue(account.id(), purpose, address, "ACTIVATE".equals(purpose) ? 1440 : 30);
        });
        deliver(delivery);
    }

    public void requestBinding(long id, String sid, String current, String email) {
        String address = AccountInput.email(email);
        var delivery = transaction.execute(status -> {
            var account = lockAuthenticated(id, sid);
            if (current == null || !passwords.matches(current, account.passwordHash())) {
                unauthorized();
            }
            if (mapper.findAccount(address).isPresent()) {
                return null;
            }
            return issue(id, "BIND_EMAIL", address, 30);
        });
        deliver(delivery);
    }

    public void confirm(String token, String purpose, String newPassword) {
        confirm(token, purpose, newPassword, null, null);
    }

    public void confirmBinding(String token, long id, String sid) {
        confirm(token, "BIND_EMAIL", null, id, sid);
    }

    private void confirm(
            String token, String purpose, String newPassword, Long expectedUser, String sid) {
        String hash = AccountSecrets.tokenDigest(token);
        String passwordHash =
                "RESET_PASSWORD".equals(purpose)
                        ? passwords.encode(AccountInput.password(newPassword))
                        : null;
        var first = mapper.action(hash).orElseThrow(AccountService::invalidToken);
        try {
            transaction.executeWithoutResult(status -> {
                var account = mapper.lockAccount(first.userId()).orElseThrow(AccountService::invalidToken);
                if (expectedUser != null) {
                    if (account.id() != expectedUser) {
                        throw invalidToken();
                    }
                    lockAuthenticated(expectedUser, sid);
                }
                var action = mapper.lockAction(hash).orElseThrow(AccountService::invalidToken);
                if (!purpose.equals(action.purpose())
                        || action.consumedAt() != null
                        || !action.expiresAt().isAfter(now())
                        || "DISABLED".equals(account.status())) {
                    throw invalidToken();
                }
                if ("ACTIVATE".equals(purpose)) {
                    if (!"PENDING_VERIFICATION".equals(account.status())
                            || !action.targetEmail().equals(account.email())
                            || mapper.activate(account.id()) != 1) {
                        throw invalidToken();
                    }
                } else if ("RESET_PASSWORD".equals(purpose)) {
                    if (!"ACTIVE".equals(account.status())
                            || account.emailVerifiedAt() == null
                            || !action.targetEmail().equals(account.email())) {
                        throw invalidToken();
                    }
                    mapper.password(account.id(), passwordHash);
                    mapper.revokeAll(account.id());
                    revoked(account.id(), null);
                    mapper.invalidateActions(account.id(), "BIND_EMAIL");
                } else if ("BIND_EMAIL".equals(purpose)) {
                    if (!"ACTIVE".equals(account.status())
                            || mapper.bindEmail(account.id(), action.targetEmail()) != 1) {
                        throw invalidToken();
                    }
                    mapper.invalidateActions(account.id(), "RESET_PASSWORD");
                } else {
                    throw invalidToken();
                }
                mapper.consumeAction(hash);
                mapper.invalidateActions(account.id(), purpose);
            });
        } catch (DuplicateKeyException conflict) {
            throw invalidToken();
        }
    }

    private AccountMapper.Account lockAuthenticated(long id, String sid) {
        var account = mapper.lockAccount(id).orElseThrow(AccountService::authenticationFailure);
        var session = mapper.lockSession(sid).orElseThrow(AccountService::authenticationFailure);
        if (!"ACTIVE".equals(account.status())
                || session.userId() != id
                || session.revokedAt() != null
                || !session.expiresAt().isAfter(now())) {
            unauthorized();
        }
        return account;
    }

    private Delivery issue(long id, String purpose, String email, int minutes) {
        if (mapper.countActions(id, purpose, 60) > 0
                || mapper.countActions(id, purpose, 3600) >= 5) {
            return null;
        }
        mapper.invalidateActions(id, purpose);
        String token = AccountSecrets.token();
        mapper.insertAction(
                new AccountMapper.Action(
                        AccountSecrets.digest(token), id, purpose, email, now().plusMinutes(minutes), null));
        return new Delivery(email, purpose, token);
    }

    private void deliver(Delivery delivery) {
        if (delivery == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // TransactionTemplate may join a caller's transaction. Never mail a rolled-back token.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(delivery);
                }
            });
        } else {
            send(delivery);
        }
    }

    private void send(Delivery delivery) {
        try {
            mail.send(delivery.email(), delivery.purpose(), delivery.token());
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(AccountService.class).warn("account.mail_delivery_failed");
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static ResponseStatusException authenticationFailure() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }

    private static void unauthorized() {
        throw authenticationFailure();
    }

    private static ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired token");
    }
}
