package com.couplefinance.identity.application;

/** A plain-text email. Contains secrets (verification tokens): never log it. */
public record EmailMessage(String to, String subject, String body) {

    @Override
    public String toString() {
        return "EmailMessage[]";
    }
}
