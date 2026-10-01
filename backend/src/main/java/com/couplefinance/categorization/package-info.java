/**
 * Categorization module: the category vocabulary (system and household custom categories, archive-only) and the
 * deterministic merchant-name normalisation (BR-CAT-07). Other modules reach it through {@code categorization.api}.
 *
 * <p>Current scope (Issues #10, #11): categories and their verification facade, merchant normalisation.
 */
@ApplicationModule(displayName = "Categorization", allowedDependencies = {"shared", "household :: api"})
package com.couplefinance.categorization;

import org.springframework.modulith.ApplicationModule;
