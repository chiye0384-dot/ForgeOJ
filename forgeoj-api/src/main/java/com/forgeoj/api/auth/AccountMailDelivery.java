package com.forgeoj.api.auth;

/** Post-commit delivery adapter. A successful send does not guarantee mailbox delivery. */
public interface AccountMailDelivery {
    void send(String recipient, String purpose, String token);
}
