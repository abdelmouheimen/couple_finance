package com.couplefinance.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void generates_version_7_ietf_variant_uuids() {
        UUID uuid = UuidV7.generate(Clock.systemUTC());

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void embeds_the_clock_time_in_milliseconds() {
        Instant instant = Instant.parse("2026-10-01T10:15:30.123Z");

        UUID uuid = UuidV7.generate(Clock.fixed(instant, ZoneOffset.UTC));

        assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(instant.toEpochMilli());
    }

    @Test
    void later_timestamps_sort_after_earlier_ones() {
        Instant earlier = Instant.parse("2026-10-01T10:15:30Z");
        UUID first = UuidV7.generate(Clock.fixed(earlier, ZoneOffset.UTC));
        UUID second = UuidV7.generate(Clock.fixed(earlier.plusMillis(1), ZoneOffset.UTC));

        assertThat(first.toString()).isLessThan(second.toString());
    }

    @Test
    void ids_generated_in_the_same_millisecond_are_distinct() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-01T10:15:30Z"), ZoneOffset.UTC);
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(UuidV7.generate(fixed));
        }

        assertThat(ids).hasSize(10_000);
    }
}
