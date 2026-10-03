package com.couplefinance.identity.domain;

import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface SessionRepository extends Repository<Session, UUID> {

    Session save(Session session);
}
