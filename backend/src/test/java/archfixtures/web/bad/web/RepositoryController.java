package archfixtures.web.bad.web;

import archfixtures.repo.good.ScopedThingRepository;

/** Violation: controller depends on a repository. */
public class RepositoryController {
    ScopedThingRepository repository;
}
