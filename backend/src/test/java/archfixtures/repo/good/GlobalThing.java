package archfixtures.repo.good;

import java.util.UUID;

/** Not household-owned (no householdId): unscoped findById is acceptable. */
public class GlobalThing {
    UUID id;
}
