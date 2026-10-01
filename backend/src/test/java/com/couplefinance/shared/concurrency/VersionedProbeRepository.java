package com.couplefinance.shared.concurrency;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface VersionedProbeRepository extends Repository<VersionedProbe, UUID> {

    VersionedProbe saveAndFlush(VersionedProbe probe);

    Optional<VersionedProbe> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);
}
