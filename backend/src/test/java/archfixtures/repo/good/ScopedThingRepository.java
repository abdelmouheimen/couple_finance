package archfixtures.repo.good;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/** Allowed: scoped finder only. */
public interface ScopedThingRepository extends Repository<OwnedThing, UUID> {
    Optional<OwnedThing> findByIdAndHouseholdId(UUID id, UUID householdId);
}
