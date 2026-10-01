package archfixtures.ai.bad;

import org.springframework.data.repository.Repository;

/** Violation (with a stand-in "SDK" package): provider SDK referenced outside ai.infrastructure. */
public class UsesSdk {
    Repository<?, ?> sdk;
}
