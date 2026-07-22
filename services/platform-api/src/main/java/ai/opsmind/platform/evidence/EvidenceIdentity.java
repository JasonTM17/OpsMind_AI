package ai.opsmind.platform.evidence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Stable application-owned UUIDv8 identities for one logical tool intent. */
public final class EvidenceIdentity {

    private EvidenceIdentity() { }

    public static UUID evidenceId(UUID organizationId, UUID runId, UUID intentId) {
        return derive("evidence", organizationId, runId, intentId);
    }

    public static UUID executionId(UUID organizationId, UUID runId, UUID intentId) {
        return derive("execution", organizationId, runId, intentId);
    }

    private static UUID derive(String type, UUID organizationId, UUID runId, UUID intentId) {
        if (organizationId == null || runId == null || intentId == null) {
            throw new IllegalArgumentException("Evidence identity scope is required.");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                ("opsmind:" + type + ":v1:" + organizationId + ":" + runId + ":" + intentId)
                    .getBytes(StandardCharsets.UTF_8)
            );
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x80);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(hash);
            return new UUID(bytes.getLong(), bytes.getLong());
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
