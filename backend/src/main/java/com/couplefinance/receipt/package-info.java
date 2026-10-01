/**
 * Receipt module. Currently contains only the pure, deterministic parsing of values transcribed from a receipt
 * (BR-RCP-04/05/08/09); no I/O, no persistence.
 */
@ApplicationModule(displayName = "Receipt", allowedDependencies = "shared")
package com.couplefinance.receipt;

import org.springframework.modulith.ApplicationModule;
