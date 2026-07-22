package ai.opsmind.platform.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class EventPayloadIntegrityTest {

    private final EventPayloadIntegrity integrity = new EventPayloadIntegrity(new ObjectMapper());

    @Test
    void acceptsValidObjectWithMatchingExactByteDigest() throws Exception {
        String payload = "{\"projectId\":\"example\"}";

        assertThatCode(() -> integrity.verify(event(payload, sha256(payload))))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsMalformedNonObjectAndMismatchedPayloads() throws Exception {
        assertThatThrownBy(() -> integrity.verify(event("{", sha256("{"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("valid JSON");
        assertThatThrownBy(() -> integrity.verify(event("[]", sha256("[]"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JSON object");
        assertThatThrownBy(() -> integrity.verify(event("{}", sha256("{ }"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match");
        assertThatThrownBy(() -> integrity.verify(event("{} {}", sha256("{} {}"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one");
    }

    private EventEnvelope event(String payload, byte[] digest) {
        return new EventEnvelope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "project",
            UUID.randomUUID(),
            1,
            "project.created",
            "1",
            null,
            UUID.randomUUID(),
            Instant.parse("2030-01-01T00:00:00Z"),
            payload,
            digest
        );
    }

    private byte[] sha256(String payload) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
    }
}
