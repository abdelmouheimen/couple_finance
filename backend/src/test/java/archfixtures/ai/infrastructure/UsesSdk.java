package archfixtures.ai.infrastructure;

import org.springframework.data.repository.Repository;

/** Allowed: stand-in SDK referenced from ai.infrastructure. */
public class UsesSdk {
    Repository<?, ?> sdk;
}
