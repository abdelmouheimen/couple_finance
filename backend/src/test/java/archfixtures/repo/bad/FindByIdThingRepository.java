package archfixtures.repo.bad;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/** Violation: declares an unscoped findById. */
public interface FindByIdThingRepository extends Repository<OwnedThing, UUID> {
    Optional<OwnedThing> findById(UUID id);
}
