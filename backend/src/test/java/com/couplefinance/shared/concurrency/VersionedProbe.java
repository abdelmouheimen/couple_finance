package com.couplefinance.shared.concurrency;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Test aggregate with a JPA {@code @Version}, backed by {@code test_support.versioned_probe}. */
@Entity
@Table(schema = "test_support", name = "versioned_probe")
public class VersionedProbe {

    @Id
    private UUID id;

    private UUID ownerUserId;

    private String name;

    @Version
    private Long version;

    protected VersionedProbe() {
    }

    UUID ownerUserId() {
        return ownerUserId;
    }

    String name() {
        return name;
    }

    long version() {
        return version;
    }

    void rename(String newName) {
        this.name = newName;
    }
}
