package com.couplefinance.identity.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.couplefinance.identity.api.AiConsentPurpose;
import com.couplefinance.identity.api.AiConsents;
import com.couplefinance.identity.domain.AiConsentLog;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.id.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC adapter of the consent log, and the {@link AiConsents} read side used by other modules. "Current consent" is
 * the latest event of the purpose (ix_consent_current); the user must still be ACTIVE.
 */
@Repository
class JdbcAiConsentLog implements AiConsentLog, AiConsents {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcAiConsentLog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<Decision> latest(UserId user, AiConsentPurpose purpose) {
        return jdbc.sql("""
                        SELECT action, consent_text_version, occurred_at FROM identity.ai_consent_event
                        WHERE user_id = :user AND purpose = :purpose
                        ORDER BY occurred_at DESC, id DESC LIMIT 1
                        """)
                .param("user", user.value())
                .param("purpose", purpose.name())
                .query((rs, n) -> new Decision("GRANT".equals(rs.getString("action")),
                        rs.getString("consent_text_version"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public void append(UserId user, AiConsentPurpose purpose, boolean granted, String textVersion, Instant at) {
        jdbc.sql("""
                        INSERT INTO identity.ai_consent_event
                            (id, user_id, purpose, action, consent_text_version, occurred_at)
                        VALUES (:id, :user, :purpose, :action, :version, :at)
                        """)
                .param("id", UuidV7.generate(clock))
                .param("user", user.value())
                .param("purpose", purpose.name())
                .param("action", granted ? "GRANT" : "WITHDRAW")
                .param("version", textVersion)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }

    @Override
    public boolean isGranted(UserId user, AiConsentPurpose purpose) {
        Boolean active = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM identity.user_account WHERE id = :user AND status = 'ACTIVE')
                        """)
                .param("user", user.value())
                .query(Boolean.class).single();
        return Boolean.TRUE.equals(active) && latest(user, purpose).map(Decision::granted).orElse(false);
    }
}
