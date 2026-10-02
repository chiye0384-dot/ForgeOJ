package com.forgeoj.api.submission;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpSession;
import com.forgeoj.api.auth.ForgeOjPrincipal;
import com.forgeoj.api.auth.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
final class SubmissionHandshakeInterceptor implements HandshakeInterceptor {
    private final SubmissionMapper mapper;
    private final AccountService accounts;

    SubmissionHandshakeInterceptor(SubmissionMapper mapper, AccountService accounts) { this.mapper = mapper; this.accounts = accounts; }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) {
        if (!sameOrigin(request)) return reject(response, HttpStatus.FORBIDDEN);
        if (!(request instanceof ServletServerHttpRequest servlet)
                || !(request.getPrincipal() instanceof Authentication authentication)
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof ForgeOjPrincipal principal)
                || !principal.isEnabled()) return reject(response, HttpStatus.UNAUTHORIZED);
        if (principal.sessionId() == null) return reject(response, HttpStatus.UNAUTHORIZED);
        String path = request.getURI().getPath();
        String prefix = "/api/v1/submissions/";
        String suffix = "/events";
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) return reject(response, HttpStatus.NOT_FOUND);
        String id = path.substring(prefix.length(), path.length() - suffix.length());
        try {
            if (!UUID.fromString(id).toString().equals(id)) return reject(response, HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException invalidId) {
            return reject(response, HttpStatus.NOT_FOUND);
        }
        SubmissionWatch watch = new SubmissionWatch(principal.userId(), id, principal.sessionId(),
                () -> accounts.authenticated(principal.userId(), principal.sessionId()));
        if (!watch.stillAuthenticated()) return reject(response, HttpStatus.UNAUTHORIZED);
        if (mapper.findNoticeByOwner(watch.userId(), id).isEmpty()) return reject(response, HttpStatus.NOT_FOUND);
        attributes.put(SubmissionWatch.ATTRIBUTE, watch);
        return true;
    }

    private boolean sameOrigin(ServerHttpRequest request) {
        var origins = request.getHeaders().get("Origin");
        if (origins == null || origins.size() != 1) return false;
        try {
            URI origin = URI.create(origins.getFirst());
            URI target = request.getURI();
            return ("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                    && origin.getHost() != null && origin.getUserInfo() == null
                    && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                    && origin.getRawQuery() == null && origin.getRawFragment() == null
                    && origin.getScheme().equalsIgnoreCase(target.getScheme())
                    && origin.getHost().equalsIgnoreCase(target.getHost())
                    && effectivePort(origin) == effectivePort(target);
        } catch (IllegalArgumentException malformedOrigin) { return false; }
    }

    private int effectivePort(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean reject(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) {}
}
