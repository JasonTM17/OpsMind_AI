package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.json.JsonMapper;

class IncidentJsonCodecTest {

    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OPERATION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private final IncidentJsonCodec codec = new IncidentJsonCodec(
        JsonMapper.builder().findAndAddModules().build()
    );

    @Test
    void cachedReplayPreservesCanonicalBodyAndMutationMetadata() {
        IncidentOperationResult original = new IncidentOperationResult(
            201,
            "{\"id\":\"" + INCIDENT_ID + "\",\"status\":\"OPEN\",\"version\":0}",
            URI.create("/api/v1/incidents/" + INCIDENT_ID),
            "\"0\"",
            OPERATION_ID
        );

        IncidentOperationResult replay = codec.replay(201, codec.cache(original));

        assertThat(replay).isEqualTo(original);
        assertThat(replay.responseBody()).isEqualTo(original.responseBody());
    }

    @Test
    void incidentBodyUsesSchemaFieldNamesAndIsoTimestamp() {
        Instant now = Instant.parse("2030-01-01T00:00:00.123456Z");
        IncidentSnapshot incident = new IncidentSnapshot(
            INCIDENT_ID,
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "API unavailable",
            "5xx spike",
            IncidentSeverity.SEV1,
            IncidentStatus.OPEN,
            null,
            null,
            OPERATION_ID,
            OPERATION_ID,
            now,
            now,
            0
        );

        String body = codec.incidentBody(incident);

        assertThat(body).contains(
            "\"id\":\"" + INCIDENT_ID + "\"",
            "\"status\":\"OPEN\"",
            "\"rootCause\":null",
            "\"createdAt\":\"2030-01-01T00:00:00.123456Z\"",
            "\"version\":0"
        );
    }

    @Test
    void malformedOrIncompleteCachedMutationFailsClosed() {
        assertThatThrownBy(() -> codec.replay(201, "{}"))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(exception.code()).isEqualTo("idempotency.record-invalid");
            });
        assertThatThrownBy(() -> codec.replay(202, "not-json"))
            .isInstanceOf(PlatformProblemException.class);
    }
}
