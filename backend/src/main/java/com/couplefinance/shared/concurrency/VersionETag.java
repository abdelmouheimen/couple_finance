package com.couplefinance.shared.concurrency;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.couplefinance.shared.error.ApplicationException;
import org.jspecify.annotations.Nullable;

/**
 * Optimistic-concurrency tokens (BR-EXP-12): the {@code ETag} of a mutable aggregate is its {@code version}
 * rendered as a strong entity tag ({@code "3"}); {@code If-Match} carries the version the client last saw. The
 * token holds nothing but a version number.
 *
 * <p>Usage in an update endpoint: {@code long expected = VersionETag.requireIfMatch(ifMatchHeader)}, load the
 * aggregate (household-scoped, so another household's resource is a 404 before any 412/428 is considered —
 * order the call accordingly), then {@link #requireMatch}. Concurrent commits between the load and the write are
 * caught by the JPA {@code @Version} check, which {@code GlobalExceptionHandler} also maps to 412.
 */
public final class VersionETag {

    // Quoted strong tag, or the bare number; at most 18 digits so the value always fits in a long.
    private static final Pattern TOKEN = Pattern.compile("^(?:\"([0-9]{1,18})\"|([0-9]{1,18}))$");

    private VersionETag() {
    }

    /** Value for the {@code ETag} response header. */
    public static String render(long version) {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        return "\"" + version + "\"";
    }

    /**
     * Parses an {@code If-Match} header into the expected version.
     *
     * @throws ApplicationException {@code IF_MATCH_REQUIRED} (428) when absent or blank, {@code IF_MATCH_INVALID}
     *     (400) when not a single version token (wildcard, weak tag, list, garbage)
     */
    public static long requireIfMatch(@Nullable String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApplicationException(ConcurrencyErrorCode.IF_MATCH_REQUIRED,
                    "The If-Match header is required to update this resource.");
        }
        Matcher matcher = TOKEN.matcher(ifMatch.strip());
        if (!matcher.matches()) {
            throw new ApplicationException(ConcurrencyErrorCode.IF_MATCH_INVALID,
                    "The If-Match header must contain the resource version.");
        }
        return Long.parseLong(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
    }

    /** @throws ApplicationException {@code VERSION_CONFLICT} (412) when the versions differ */
    public static void requireMatch(long expectedVersion, long currentVersion) {
        if (expectedVersion != currentVersion) {
            throw versionConflict();
        }
    }

    public static ApplicationException versionConflict() {
        return new ApplicationException(ConcurrencyErrorCode.VERSION_CONFLICT,
                "The resource was modified since the version you hold. Reload it and retry.");
    }
}
