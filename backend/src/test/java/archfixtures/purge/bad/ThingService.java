package archfixtures.purge.bad;

/** Violation: a non-purge class uses the unscoped purge repository. */
public class ThingService {
    private final ThingPurgeRepository purges;

    ThingService(ThingPurgeRepository purges) {
        this.purges = purges;
    }

    int run() {
        return purges.purge();
    }
}
