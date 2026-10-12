/**
 * Receipt module: evidence (architecture.md section 3). Hexagonal layout: {@code domain} (receipt aggregate, upload
 * policy, deterministic parsing of transcribed values BR-RCP-04/05/08/09, persistence ports), {@code application}
 * (use cases and the outbound ports DocumentStorage, MalwareScanner, PageNormaliser), {@code infrastructure}
 * (JDBC, S3, ClamAV and JDK/PDFBox adapters) and {@code web}.
 *
 * <p>Scope: secure upload, storage and the receipt vault (DOC-001), asynchronous analysis into a review draft
 * (DOC-003), review, duplicate warnings and exactly-once confirmation or attachment through {@code expense.api}
 * (DOC-004, ADR-006).
 */
@ApplicationModule(displayName = "Receipt", allowedDependencies = {"shared", "household :: api", "identity :: api",
        "ai :: api", "categorization :: api", "expense :: api"})
package com.couplefinance.receipt;

import org.springframework.modulith.ApplicationModule;
