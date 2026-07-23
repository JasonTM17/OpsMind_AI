package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.common.api.OperatorProjection;

import org.junit.jupiter.api.Test;

class OperatorIncidentProjectionTest {

    @Test
    void redactsOperatorVisibleLeavesAndPreservesNullFields() {
        String credential = "opaque-" + "credential".repeat(4);
        IncidentResponse source = new IncidentResponse(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "Contact ops@example.test",
            "Authorization: Bearer " + credential,
            IncidentSeverity.SEV2, IncidentStatus.INVESTIGATING,
            null, null, UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-01-01T00:01:00Z"), 2
        );

        OperatorProjection<OperatorIncidentProjection> result =
            OperatorIncidentProjection.from(source);

        assertThat(result.redactionCount()).isEqualTo(2);
        assertThat(result.body().title()).isEqualTo("Contact [REDACTED_EMAIL]");
        assertThat(result.body().summary()).isEqualTo("[REDACTED_SECRET]");
        assertThat(result.body().rootCause()).isNull();
        assertThat(result.body().resolutionSummary()).isNull();
    }
}
