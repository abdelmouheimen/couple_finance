package archfixtures.clock.bad;

import java.time.Clock;

/** Violation: creating the system clock outside shared.time. */
public class CreatesSystemClock {
    Clock clock() {
        return Clock.systemUTC();
    }
}
