package com.couplefinance.platform.authz;

import java.util.UUID;

/** Who calls, against which resources. */
public record Scenario(UUID caller, SeededHousehold target) {
}
