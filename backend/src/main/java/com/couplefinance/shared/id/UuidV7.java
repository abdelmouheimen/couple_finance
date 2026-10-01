package com.couplefinance.shared.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Generates RFC 9562 version 7 UUIDs: 48-bit Unix timestamp in milliseconds followed by random bits.
 * Time-ordered for B-tree locality, not enumerable (database-schema.md §1).
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate(Clock clock) {
        long timestamp = clock.millis() & 0xFFFF_FFFF_FFFFL;
        long randA = RANDOM.nextLong() & 0x0FFFL;
        long mostSignificant = (timestamp << 16) | 0x7000L | randA;
        long leastSignificant = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
