package com.forgeoj.api.auth;

import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Message unavailable() {
        return new Message("Authentication temporarily unavailable.");
    }


    private final AccountService accounts;
    private final AccountJwt jwt;
    private final AccountCookies cookies;
    private final AccountCsrfRepository csrf;
    private final AccountRateLimiter limits;

    public AuthController(
            AccountService accounts,
            AccountJwt jwt,
            AccountCookies cookies,
            AccountCsrfRepository csrf,
            AccountRateLimiter limits) {
        this.accounts = accounts;
        this.jwt = jwt;
        this.cookies = cookies;
        this.csrf = csrf;
        this.limits = limits;
    }

    @GetMapping("/session")
    SessionResponse session(
            Authentication authentication, CsrfToken csrfToken, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return toResponse(authentication, csrfToken);
    }

    @PostMapping("/login")
    SessionResponse login(
            @RequestBody LoginRequest input,
            HttpServletRequest request,
            HttpServletResponse response) {
        limits.check("login-ip:" + request.getRemoteAddr(), 30, 300);
        limits.check(
                "login-id:" + AccountSecrets.digest(
                        input.username() == null ? "" : input.username().strip().toLowerCase(Locale.ROOT)),
                10,
                300);
        var login = accounts.login(input.username(), input.password());
        cookies.login(response, login, jwt.issue(login));
        var principal =
                new ForgeOjPrincipal(login.userId(), login.username(), "", true, login.sessionId());
        response.setHeader("Cache-Control", "no-store");
        return toResponse(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()),
                csrf.rotate(request, response));
    }

    @PostMapping("/refresh")
    SessionResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        limits.check("refresh-ip:" + request.getRemoteAddr(), 60, 300);
        var login = accounts.refresh(AccountCookies.read(request, AccountCookies.REFRESH));
        cookies.login(response, login, jwt.issue(login));
        var principal =
                new ForgeOjPrincipal(login.userId(), login.username(), "", true, login.sessionId());
        response.setHeader("Cache-Control", "no-store");
        return toResponse(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()),
                csrf.loadToken(request));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        if (authentication != null && authentication.getPrincipal() instanceof ForgeOjPrincipal p) {
            accounts.logoutCurrent(p.userId(), p.sessionId());
        } else {
            accounts.logout(AccountCookies.read(request, AccountCookies.REFRESH));
        }
        cookies.clear(response);
        csrf.rotate(request, response);
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logoutAll(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        var p = principal(authentication);
        accounts.logoutAll(p.userId(), p.sessionId());
        cookies.clear(response);
        csrf.rotate(request, response);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Message register(@RequestBody Registration input, HttpServletRequest request) {
        mailLimit(request);
        accounts.register(input.username(), input.email(), input.password(), input.nickname());
        return accepted();
    }

    @PostMapping("/email-verification/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Message verification(@RequestBody Email input, HttpServletRequest request) {
        mailLimit(request);
        accounts.request(input.email(), "ACTIVATE");
        return accepted();
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Message resetRequest(@RequestBody Email input, HttpServletRequest request) {
        mailLimit(request);
        accounts.request(input.email(), "RESET_PASSWORD");
        return accepted();
    }

    @PostMapping("/email-binding/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Message binding(
            @RequestBody Binding input, Authentication authentication, HttpServletRequest request) {
        mailLimit(request);
        var p = principal(authentication);
        accounts.requestBinding(p.userId(), p.sessionId(), input.password(), input.email());
        return accepted();
    }

    @PostMapping("/email-verification/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void activate(@RequestBody Token input, HttpServletRequest request) {
        consumeLimit(request);
        accounts.confirm(input.token(), "ACTIVATE", null);
    }

    @PostMapping("/email-binding/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmBinding(
            @RequestBody Token input, Authentication authentication, HttpServletRequest request) {
        consumeLimit(request);
        var p = principal(authentication);
        accounts.confirmBinding(input.token(), p.userId(), p.sessionId());
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(
            @RequestBody Reset input, HttpServletRequest request, HttpServletResponse response) {
        consumeLimit(request);
        accounts.confirm(input.token(), "RESET_PASSWORD", input.password());
        cookies.clear(response);
        csrf.rotate(request, response);
    }

    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void change(
            @RequestBody Change input,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        consumeLimit(request);
        var p = principal(authentication);
        accounts.changePassword(
                p.userId(), p.sessionId(), input.currentPassword(), input.password());
        cookies.clear(response);
        csrf.rotate(request, response);
    }

    private void mailLimit(HttpServletRequest request) {
        limits.check("mail-ip:" + request.getRemoteAddr(), 20, 3600);
    }

    private void consumeLimit(HttpServletRequest request) {
        limits.check("consume-ip:" + request.getRemoteAddr(), 30, 300);
    }

    private static Message accepted() {
        return new Message("If eligible, an email will be sent. Please check your mailbox.");
    }

    private static ForgeOjPrincipal principal(Authentication auth) {
        return (ForgeOjPrincipal) auth.getPrincipal();
    }

    private static SessionResponse toResponse(Authentication auth, CsrfToken token) {
        var csrf = new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
        if (auth != null
                && auth.isAuthenticated()
                && auth.getPrincipal() instanceof ForgeOjPrincipal p) {
            return new SessionResponse(
                    true, new SessionUserResponse(p.userId(), p.getUsername()), csrf);
        }
        return new SessionResponse(false, null, csrf);
    }

    public record LoginRequest(String username, String password) {}

    public record Registration(String username, String email, String password, String nickname) {}

    public record Email(String email) {}

    public record Token(String token) {}

    public record Reset(String token, String password) {}

    public record Change(String currentPassword, String password) {}

    public record Binding(String password, String email) {}

    public record Message(String message) {}

    public record SessionResponse(
            boolean authenticated, SessionUserResponse user, CsrfResponse csrf) {}

    public record SessionUserResponse(long id, String username) {}

    public record CsrfResponse(String headerName, String parameterName, String token) {}
}
