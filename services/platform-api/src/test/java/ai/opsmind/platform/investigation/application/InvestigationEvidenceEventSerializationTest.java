package ai.opsmind.platform.investigation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.evidence.CollectedEvidence;
import ai.opsmind.platform.investigation.domain.InvestigationEvent;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class InvestigationEvidenceEventSerializationTest {

    @Test
    void evidenceEventPersistsOnlyMetadataAndNeverCanonicalContent() {
        UUID runId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        String content = "{\"authorization\":\"[REDACTED]\"}";
        String digest = "sha256:35dc6144ff36675ee40d1a531281b43d54e93aefcc2377cef1ad85678fdaa0f8";
        CollectedEvidence collected = new CollectedEvidence(
            UUID.randomUUID(), UUID.randomUUID(), "0".repeat(64), "metric",
            "fixture-prometheus", "prometheus:synthetic/opsmind-api",
            Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2029-12-31T23:57:00Z"),
            Instant.parse("2030-01-01T00:00:00Z"), "fixture-observability@1",
            "observability.metrics.query@1", "policy-fixture-v1",
            "fixture-prometheus/fixture-observability@1", "synthetic", digest,
            content, 1, false, null, false
        );
        InvestigationEvent.EvidenceAppended event = new InvestigationEvent.EvidenceAppended(
            runId, UUID.randomUUID(), evidenceId, digest, "metric", collected,
            Instant.parse("2030-01-01T00:00:01Z")
        );
        InvestigationPersistenceJsonCodec codec = new InvestigationPersistenceJsonCodec(
            JsonMapper.builder().findAndAddModules().build()
        );

        String payload = codec.eventPayload(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            runId, 4, UUID.randomUUID(), event
        );

        assertThat(payload).contains(evidenceId.toString());
        assertThat(payload).doesNotContain(
            content, "canonicalContent", "gatewayAuditEventId",
            collected.executionId().toString(), collected.gatewayRequestDigest()
        );
    }
}
