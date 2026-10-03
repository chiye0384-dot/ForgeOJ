package com.forgeoj.api.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default delivery mode: deliberately makes no network connection. */
@Component
@ConditionalOnProperty(name = "forgeoj.auth.mail.mode", havingValue = "disabled", matchIfMissing = true)
public final class DisabledAccountMail implements AccountMailDelivery {
    @Override
    public void send(String recipient, String purpose, String token) {
        throw new IllegalStateException("Account mail delivery is disabled");
    }
}
