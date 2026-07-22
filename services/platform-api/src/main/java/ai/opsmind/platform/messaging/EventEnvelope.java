package ai.opsmind.platform.messaging;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope(
    UUID eventId,
    UUID organizationId,
    String aggregateType,
    UUID aggregateId,
    long aggregateSequence,
    String eventType,
    String schemaVersion,
    UUID causationId,
    UUID correlationId,
    Instant occurredAt,
    String payloadJson,
    byte[] payloadDigest
) {
    public EventEnvelope {
        if (eventId == null || organizationId == null || aggregateId == null || correlationId == null) {
            throw new IllegalArgumentException("Event identity fields are required.");
        }
        if (aggregateType == null || aggregateType.isBlank() || aggregateType.length() > 128
            || eventType == null || eventType.isBlank() || eventType.length() > 160) {
            throw new IllegalArgumentException("Event type fields are required.");
        }
        if (aggregateSequence < 1 || schemaVersion == null || schemaVersion.isBlank()
            || schemaVersion.length() > 32) {
            throw new IllegalArgumentException("Event sequence and schema version are invalid.");
        }
        if (occurredAt == null || payloadJson == null || payloadJson.isBlank()
            || payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) {
            throw new IllegalArgumentException("Event time and payload are required.");
        }
        if (payloadDigest == null || payloadDigest.length != 32) {
            throw new IllegalArgumentException("Event payload digest must be SHA-256 bytes.");
        }
        payloadDigest = payloadDigest.clone();
    }

    @Override
    public byte[] payloadDigest() {
        return payloadDigest.clone();
    }
}
