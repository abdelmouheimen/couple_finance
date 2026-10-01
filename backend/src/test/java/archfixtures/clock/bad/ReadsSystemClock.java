package archfixtures.clock.bad;

import java.time.Instant;
import java.time.LocalDate;

/** Violation: direct system-clock reads. */
public class ReadsSystemClock {
    Instant instant() {
        return Instant.now();
    }

    LocalDate date() {
        return LocalDate.now();
    }
}
