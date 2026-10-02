package com.couplefinance.platform.authz;

import java.util.Set;

import org.springframework.http.HttpMethod;

/** One operation of the OpenAPI contract. */
public record ApiOperation(String operationId, HttpMethod method, String path, boolean secured) {

    private static final Set<HttpMethod> SAFE = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    public boolean isWrite() {
        return !SAFE.contains(method);
    }

    @Override
    public String toString() {
        return operationId + " (" + method + " " + path + ")";
    }
}
