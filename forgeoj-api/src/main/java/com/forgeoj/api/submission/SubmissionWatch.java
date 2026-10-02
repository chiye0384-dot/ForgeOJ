package com.forgeoj.api.submission;

import jakarta.servlet.http.HttpSession;

import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

// Server-side only; never serialize the session or authentication into a notification.
record SubmissionWatch(long userId, String submissionId, HttpSession loginSession) {
    static final String ATTRIBUTE = SubmissionWatch.class.getName();

    boolean stillAuthenticated() {
        try {
            Object stored = loginSession.getAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (!(stored instanceof SecurityContext context)) return false;
            Authentication authentication = context.getAuthentication();
            return authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof ForgeOjPrincipal principal
                    && principal.isEnabled() && principal.userId() == userId;
        } catch (IllegalStateException expiredOrInvalidated) {
            return false;
        }
    }
}
