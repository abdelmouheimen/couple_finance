package archfixtures.repo.bad;

import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

/** Violation: inherits findById/findAll. */
public interface CrudThingRepository extends CrudRepository<OwnedThing, UUID> {}
