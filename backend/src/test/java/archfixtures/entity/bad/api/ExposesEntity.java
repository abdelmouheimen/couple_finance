package archfixtures.entity.bad.api;

import archfixtures.entity.bad.domain.SomeEntity;

/** Violation: module api exposes a JPA entity. */
public interface ExposesEntity {
    SomeEntity find();
}
