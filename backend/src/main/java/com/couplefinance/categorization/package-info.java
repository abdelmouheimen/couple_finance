/**
 * Categorization module. Currently contains only the deterministic merchant-name normalisation
 * (BR-CAT-07), exposed to other modules through {@code categorization.api}.
 */
@ApplicationModule(displayName = "Categorization", allowedDependencies = "shared")
package com.couplefinance.categorization;

import org.springframework.modulith.ApplicationModule;
