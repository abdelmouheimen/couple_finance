package archfixtures.repo.good;

import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

/** Allowed exception (security.md 4.4): dedicated purge repository. */
public interface ThingPurgeRepository extends CrudRepository<OwnedThing, UUID> {}
