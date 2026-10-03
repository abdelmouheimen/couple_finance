package com.couplefinance.identity.application;

/** Tokens issued by a login. The plaintext refresh token exists only here and in the HTTP response. */
public record LoginResult(String accessToken, String refreshToken, long expiresInSeconds) {

    @Override
    public String toString() {
        return "LoginResult[]";
    }
}
