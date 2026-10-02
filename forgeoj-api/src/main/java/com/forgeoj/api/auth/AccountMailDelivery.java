package com.forgeoj.api.auth;

/** Replace this development adapter with a configured delivery provider before deployment. */
public interface AccountMailDelivery {
    void send(String recipient, String purpose, String token);
}
