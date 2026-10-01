package com.couplefinance.shared.pagination;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param cursorKey base64 encoded 32-byte cursor encryption key ({@code COUPLEFINANCE_PAGINATION_CURSOR_KEY}).
 *     When unset a random key is generated at startup: cursors then do not survive a restart and are not valid
 *     across instances, so production must configure it.
 */
@ConfigurationProperties("couplefinance.pagination")
public record CursorProperties(@Nullable String cursorKey) {}
