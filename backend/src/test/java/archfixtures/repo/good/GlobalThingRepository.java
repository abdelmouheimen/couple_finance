package archfixtures.repo.good;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface GlobalThingRepository extends Repository<GlobalThing, UUID> {
    Optional<GlobalThing> findById(UUID id);
}
