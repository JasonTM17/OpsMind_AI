package ai.opsmind.toolgateway.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;

import ai.opsmind.toolgateway.application.NonceReplayStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcNonceReplayStore implements NonceReplayStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcNonceReplayStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public boolean available() {
        try {
            Boolean available = jdbc.queryForObject(
                "SELECT to_regclass('tool_gateway.capability_nonce_claims') IS NOT NULL "
                    + "AND has_table_privilege(current_user, "
                    + "'tool_gateway.capability_nonce_claims', 'SELECT') "
                    + "AND has_table_privilege(current_user, "
                    + "'tool_gateway.capability_nonce_claims', 'INSERT') "
                    + "AND has_table_privilege(current_user, "
                    + "'tool_gateway.capability_nonce_claims', 'DELETE')",
                Boolean.class
            );
            return Boolean.TRUE.equals(available);
        }
        catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public boolean claim(String nonce, Instant expiresAt) {
        if (nonce == null || nonce.isBlank() || nonce.length() > 128 || expiresAt == null) {
            throw new IllegalArgumentException("Capability nonce claim is invalid.");
        }
        Boolean claimed = transactions.execute(status -> {
            jdbc.update(
                "DELETE FROM tool_gateway.capability_nonce_claims "
                    + "WHERE expires_at <= transaction_timestamp()"
            );
            return jdbc.update(
                "INSERT INTO tool_gateway.capability_nonce_claims "
                    + "(nonce_hash, expires_at) SELECT ?, ? "
                    + "WHERE ? > transaction_timestamp() ON CONFLICT DO NOTHING",
                digest(nonce), Timestamp.from(expiresAt), Timestamp.from(expiresAt)
            ) == 1;
        });
        return Boolean.TRUE.equals(claimed);
    }

    private byte[] digest(String nonce) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                nonce.getBytes(StandardCharsets.UTF_8)
            );
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
