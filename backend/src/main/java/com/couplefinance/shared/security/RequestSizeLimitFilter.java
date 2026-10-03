package com.couplefinance.shared.security;

import java.io.IOException;

import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.error.CommonErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Rejects oversized bodies on unauthenticated routes before they are read (security.md §5). The declared
 * {@code Content-Length} is checked; a body without a declared length (chunked) is rejected too, since the routes
 * concerned carry small JSON documents that clients send with a length.
 */
final class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final String pathPrefix;
    private final long maxBytes;
    private final HandlerExceptionResolver exceptionResolver;

    RequestSizeLimitFilter(String pathPrefix, long maxBytes, HandlerExceptionResolver exceptionResolver) {
        this.pathPrefix = pathPrefix;
        this.maxBytes = maxBytes;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(pathPrefix);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        boolean hasBody = length > 0 || request.getHeader("Transfer-Encoding") != null;
        if (length > maxBytes || (hasBody && length < 0)) {
            exceptionResolver.resolveException(request, response, null,
                    new ApplicationException(CommonErrorCode.PAYLOAD_TOO_LARGE, "The request body is too large."));
            return;
        }
        chain.doFilter(request, response);
    }
}
