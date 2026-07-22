package ai.opsmind.platform.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class EvidenceContentCanonicalizerTest {

    private static final String FIXTURE_JSON =
        "{\"authorization\":\"[REDACTED]\",\"metric\":\"http_errors_total\",\"service\":\"opsmind-api\"}";
    private static final String FIXTURE_DIGEST =
        "sha256:f1c78bf4c750e01ebf7387529739e69de60c6af1525bca87fa0ea1991516e2db";

    private final EvidenceContentCanonicalizer canonicalizer =
        new EvidenceContentCanonicalizer(new ObjectMapper());

    @Test
    void matchesToolGatewayFixtureAndIgnoresObjectInsertionOrder() {
        LinkedHashMap<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("service", "opsmind-api");
        reordered.put("metric", "http_errors_total");
        reordered.put("authorization", "[REDACTED]");

        EvidenceContentCanonicalizer.CanonicalEvidenceContent result =
            canonicalizer.canonicalize(reordered);

        assertThat(result.json()).isEqualTo(FIXTURE_JSON);
        assertThat(result.digest()).isEqualTo(FIXTURE_DIGEST);
        assertThat(canonicalizer.verify(FIXTURE_JSON, FIXTURE_DIGEST)).isEqualTo(result);
    }

    @Test
    void rejectsDigestDriftAndUnredactedSensitiveValues() {
        assertThatThrownBy(() -> canonicalizer.verify(
            FIXTURE_JSON.replace("http_errors_total", "http_requests_total"), FIXTURE_DIGEST
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("digest");

        assertThatThrownBy(() -> canonicalizer.canonicalize(Map.of(
            "authorization", "Bearer abcdefghijklmnop"
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("redacted");

        assertThat(canonicalizer.canonicalize(Map.of("authorization", "[REDACTED]")))
            .extracting(EvidenceContentCanonicalizer.CanonicalEvidenceContent::json)
            .isEqualTo("{\"authorization\":\"[REDACTED]\"}");
    }

    @Test
    void derivesStableScopedEvidenceAndExecutionIdentities() {
        UUID organizationId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID runId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID intentId = UUID.fromString("33333333-3333-4333-8333-333333333333");

        UUID evidenceId = EvidenceIdentity.evidenceId(organizationId, runId, intentId);
        assertThat(EvidenceIdentity.evidenceId(organizationId, runId, intentId)).isEqualTo(evidenceId);
        assertThat(evidenceId.version()).isEqualTo(8);
        assertThat(EvidenceIdentity.executionId(organizationId, runId, intentId))
            .isNotEqualTo(evidenceId);
        assertThat(EvidenceIdentity.evidenceId(organizationId, runId, UUID.randomUUID()))
            .isNotEqualTo(evidenceId);
    }
}
