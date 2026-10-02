package archfixtures.purge.good;

/** Allowed: a dedicated purge class. */
public class ThingPurgeService {
    private final ThingPurgeRepository purges;

    ThingPurgeService(ThingPurgeRepository purges) {
        this.purges = purges;
    }

    int run() {
        return purges.purge();
    }
}
