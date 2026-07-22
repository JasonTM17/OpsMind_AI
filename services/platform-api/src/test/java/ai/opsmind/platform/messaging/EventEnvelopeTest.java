package ai.opsmind.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    @Test
    void defensivelyCopiesDigestAndValidatesShape() {
        byte[] digest = new byte[32];
        EventEnvelope event = new EventEnvelope(
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
            "{}",
            digest
        );

        digest[0] = 7;

        assertThat(event.payloadDigest()[0]).isZero();
        assertThatThrownBy(() -> new EventEnvelope(
            UUID.randomUUID(), UUID.randomUUID(), "project", UUID.randomUUID(), 0,
            "project.created", "1", null, UUID.randomUUID(), Instant.now(), "{}", new byte[32]
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
