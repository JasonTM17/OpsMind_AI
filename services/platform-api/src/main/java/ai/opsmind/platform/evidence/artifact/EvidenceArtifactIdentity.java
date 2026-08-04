package ai.opsmind.platform.evidence.artifact;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Stable UUIDv8 identities for one scoped artifact intent and its lifecycle events. */
public final class EvidenceArtifactIdentity {

    private EvidenceArtifactIdentity() { }

    public static UUID artifactId(UUID organizationId, UUID runId, UUID idempotencyKey) {
        return derive("artifact", organizationId, runId, idempotencyKey);
    }

    public static UUID initialEventId(UUID organizationId, UUID artifactId) {
        return derive("artifact-event", organizationId, artifactId, artifactId);
    }

    public static UUID lifecycleEventId(
        UUID organizationId,
        UUID artifactId,
        long lifecycleVersion,
        UUID uploadAttemptId
    ) {
        if (lifecycleVersion < 1 || uploadAttemptId == null) {
            throw new IllegalArgumentException("Artifact lifecycle event identity is invalid.");
        }
        return deriveLifecycleEvent(organizationId, artifactId, lifecycleVersion, uploadAttemptId);
    }

    /** Identity for a Phase 4C control transition (no upload attempt involved). */
    public static UUID controlEventId(UUID organizationId, UUID artifactId, long lifecycleVersion) {
        if (organizationId == null || artifactId == null || lifecycleVersion < 3) {
            throw new IllegalArgumentException("Artifact control event identity is invalid.");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                ("opsmind:artifact-control-event:v1:" + organizationId + ":" + artifactId + ":"
                    + lifecycleVersion).getBytes(StandardCharsets.UTF_8)
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

    private static UUID derive(String type, UUID organizationId, UUID primaryId, UUID secondaryId) {
        if (organizationId == null || primaryId == null || secondaryId == null) {
            throw new IllegalArgumentException("Artifact identity scope is required.");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                ("opsmind:" + type + ":v1:" + organizationId + ":" + primaryId + ":" + secondaryId)
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

    private static UUID deriveLifecycleEvent(
        UUID organizationId,
        UUID artifactId,
        long lifecycleVersion,
        UUID uploadAttemptId
    ) {
        if (organizationId == null || artifactId == null) {
            throw new IllegalArgumentException("Artifact identity scope is required.");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                ("opsmind:artifact-event:v2:" + organizationId + ":" + artifactId + ":"
                    + lifecycleVersion + ":" + uploadAttemptId).getBytes(StandardCharsets.UTF_8)
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
