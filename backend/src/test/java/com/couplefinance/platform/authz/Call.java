package com.couplefinance.platform.authz;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;

/**
 * A concrete HTTP request of a fixture.
 *
 * @param files multipart {@code files} parts (receipt upload); {@code null} for JSON or body-less requests
 */
public record Call(HttpMethod method, String uri, @Nullable String body, @Nullable String ifMatch,
        @Nullable List<byte[]> files) {

    public static Call get(String uri) {
        return new Call(HttpMethod.GET, uri, null, null, null);
    }

    public static Call without(HttpMethod method, String uri) {
        return new Call(method, uri, null, null, null);
    }

    public static Call with(HttpMethod method, String uri, String body) {
        return new Call(method, uri, body, null, null);
    }

    public static Call withIfMatch(HttpMethod method, String uri, String body, String ifMatch) {
        return new Call(method, uri, body, ifMatch, null);
    }

    /** A multipart POST of the given files as {@code files} parts. */
    public static Call multipart(String uri, List<byte[]> files) {
        return new Call(HttpMethod.POST, uri, null, null, List.copyOf(files));
    }
}
