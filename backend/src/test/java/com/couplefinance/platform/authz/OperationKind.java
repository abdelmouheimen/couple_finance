package com.couplefinance.platform.authz;

/** How an OpenAPI operation is secured; every operation of {@code api/openapi.yaml} must be classified. */
public enum OperationKind {
    /** No authentication required (explicit allow-list; the contract must declare {@code security: []}). */
    PUBLIC,
    /** Requires a valid token but touches no household resource by id (e.g. creating the household itself). */
    AUTHENTICATED_ONLY,
    /** Reads or writes household data: isolation, privacy and dissolution rules apply. */
    HOUSEHOLD_SCOPED
}
