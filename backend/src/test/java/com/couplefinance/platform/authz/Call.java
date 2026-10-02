package com.couplefinance.platform.authz;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;

/** A concrete HTTP request of a fixture. */
public record Call(HttpMethod method, String uri, @Nullable String body, @Nullable String ifMatch) {

    public static Call get(String uri) {
        return new Call(HttpMethod.GET, uri, null, null);
    }

    public static Call without(HttpMethod method, String uri) {
        return new Call(method, uri, null, null);
    }

    public static Call with(HttpMethod method, String uri, String body) {
        return new Call(method, uri, body, null);
    }

    public static Call withIfMatch(HttpMethod method, String uri, String body, String ifMatch) {
        return new Call(method, uri, body, ifMatch);
    }
}
