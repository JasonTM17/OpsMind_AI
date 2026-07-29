package ai.opsmind.platform.evidence.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.audit.AuditEvent;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisScope;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EvidenceArtifactAuditPayloadCodecTest {

    @Test
    void emitsOnlyTheExactMetadataAuditEnvelope() throws Exception {
        UUID organizationId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID runId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID idempotencyKey = UUID.fromString("33333333-3333-4333-8333-333333333333");
        Instant occurredAt = Instant.parse("2030-01-01T00:00:00Z");
        EvidenceArtifactMetadata metadata = EvidenceArtifactMetadata.pendingUpload(
            new AuthorizedIncidentAnalysisScope(
                organizationId,
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                4L
            ),
            new EvidenceArtifactCreateCommand(
                idempotencyKey, runId, "metric", "prometheus:synthetic/opsmind-api", "v1",
                "redacted-metrics", EvidenceArtifactDigest.parse("sha256:" + "c".repeat(64)), 4_096
            ),
            EvidenceArtifactIdentity.artifactId(organizationId, runId, idempotencyKey),
            occurredAt
        );
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        UUID eventId = EvidenceArtifactIdentity.initialEventId(organizationId, metadata.artifactId());

        AuditEvent audit = new EvidenceArtifactAuditPayloadCodec(mapper)
            .pendingUpload(metadata, eventId, occurredAt);
        JsonNode payload = mapper.readTree(audit.payloadJson());

        assertThat(audit.schemaVersion()).isEqualTo(AuditEvent.EVIDENCE_ARTIFACT_SCHEMA_VERSION);
        assertThat(audit.resourceId()).isEqualTo(metadata.artifactId().toString());
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder(Set.of(
            "eventId", "organizationId", "projectId", "incidentId", "runId", "artifactId",
            "actorId", "lifecycleVersion", "lifecycleState", "contentDigest", "byteCount",
            "dataClassification", "retentionClass", "occurredAt"
        ));
        assertThat(audit.payloadJson()).doesNotContain(
            "storageKey", "encryption", "credential", "objectUrl", "raw"
        );
    }
}
