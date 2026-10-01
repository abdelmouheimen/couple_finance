package archfixtures.web.bad.web;

import archfixtures.web.bad.infrastructure.JdbcAdapter;

/** Violation: controller depends on an infrastructure class. */
public class InfrastructureController {
    JdbcAdapter adapter;
}
