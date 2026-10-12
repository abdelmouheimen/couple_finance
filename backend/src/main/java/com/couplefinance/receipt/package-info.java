/**
 * Receipt module: evidence (architecture.md section 3). Hexagonal layout: {@code domain} (receipt aggregate, upload
 * policy, deterministic parsing of transcribed values BR-RCP-04/05/08/09, persistence ports), {@code application}
 * (use cases and the outbound ports DocumentStorage, MalwareScanner, PageNormaliser), {@code infrastructure}
 * (JDBC, S3, ClamAV and JDK/PDFBox adapters) and {@code web}.
 *
 * <p>Current scope (DOC-001): secure upload, storage and the receipt vault. Analysis and confirmation follow.
 */
@ApplicationModule(displayName = "Receipt", allowedDependencies = {"shared", "household :: api"})
package com.couplefinance.receipt;

import org.springframework.modulith.ApplicationModule;
