package com.forgeoj.api.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String previous = MDC.get("requestId");
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        boolean returned = false;
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
            returned = true;
        } finally {
            try {
                LOGGER.atInfo().addKeyValue("event", "request.completed")
                        .addKeyValue("method", METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER")
                        .addKeyValue("route", route(request.getRequestURI()))
                        .addKeyValue("httpStatus", returned ? response.getStatus() : 500)
                        .addKeyValue("durationMillis", (System.nanoTime() - started) / 1_000_000)
                        .log("HTTP request completed");
            } catch (RuntimeException loggingFailure) {
                // Keep the original HTTP outcome even if a logging sink fails.
            } finally {
                if (previous == null) MDC.remove("requestId");
                else MDC.put("requestId", previous);
            }
        }
    }

    private static String route(String path) {
        // Classification only: never output raw paths, slugs or query strings.
        if (path.startsWith("/api/v1/submissions/")) {
            if (path.endsWith("/cancel")) return "submission.cancel";
            if (path.endsWith("/events")) return "submission.events";
            return "submission.get";
        }
        if (path.startsWith("/api/v1/problems/")) {
            return path.endsWith("/submissions") ? "submission.create" : "problem.get";
        }
        if (path.startsWith("/api/v1/auth/")) return "auth";
        return "other";
    }
}
