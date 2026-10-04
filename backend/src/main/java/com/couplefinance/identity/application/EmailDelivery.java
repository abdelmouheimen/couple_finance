package com.couplefinance.identity.application;

/**
 * Outbound email port (no vendor coupling). Implementations are adapters in {@code infrastructure}; callers never
 * invoke it inside a database transaction (CLAUDE.md 6.3).
 */
public interface EmailDelivery {

    void send(EmailMessage message);
}
