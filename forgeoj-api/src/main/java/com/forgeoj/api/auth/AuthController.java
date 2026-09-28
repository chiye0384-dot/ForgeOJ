package com.forgeoj.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    }

    @GetMapping("/session")
    SessionResponse session(Authentication authentication, CsrfToken csrfToken) {
        return toResponse(authentication, csrfToken);
    }

    @PostMapping("/login")
    SessionResponse login(
            @RequestBody LoginRequest loginRequest,
            CsrfToken csrfToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (loginRequest.username() == null
                || loginRequest.username().isBlank()
                || loginRequest.password() == null
                || loginRequest.password().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "username and password are required");
        }

        try {
            Authentication authentication =
                    authenticationManager.authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated(
                                    loginRequest.username(), loginRequest.password()));
            sessionAuthenticationStrategy.onAuthentication(authentication, request, response);

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            return toResponse(authentication, csrfToken);
        } catch (AuthenticationException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
    }

    private SessionResponse toResponse(Authentication authentication, CsrfToken csrfToken) {
        CsrfResponse csrf =
                new CsrfResponse(
                        csrfToken.getHeaderName(),
                        csrfToken.getParameterName(),
                        csrfToken.getToken());

        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof ForgeOjPrincipal principal) {
            return new SessionResponse(
                    true, new SessionUserResponse(principal.userId(), principal.getUsername()), csrf);
        }
        return new SessionResponse(false, null, csrf);
    }

    public record LoginRequest(String username, String password) {}

    public record SessionResponse(
            boolean authenticated, SessionUserResponse user, CsrfResponse csrf) {}

    public record SessionUserResponse(long id, String username) {}

    public record CsrfResponse(String headerName, String parameterName, String token) {}
}
