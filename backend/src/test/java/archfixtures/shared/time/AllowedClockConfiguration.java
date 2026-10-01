package archfixtures.shared.time;

import java.time.Clock;

/** Allowed location: shared.time may create the system clock. */
public class AllowedClockConfiguration {
    Clock clock() {
        return Clock.systemUTC();
    }
}
