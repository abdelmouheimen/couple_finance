package archfixtures.clock.good;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/** Allowed: time derived from an injected Clock. */
public class UsesInjectedClock {
    Instant instant(Clock clock) {
        return Instant.now(clock);
    }

    LocalDate date(Clock clock) {
        return LocalDate.now(clock);
    }
}
