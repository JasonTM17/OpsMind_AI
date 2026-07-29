package ai.opsmind.platform.evidence.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.audit.AuditEvent;
import ai.opsmind.platform.audit.AuditRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

class EvidenceArtifactStoredLifecycleAppenderTest {

    @Test
    void appendsTheAttemptBoundLifecycleEventBeforeItsExactStoredAudit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AuditRepository auditRepository = mock(AuditRepository.class);
        EvidenceArtifactStoredLifecycleAppender appender = new EvidenceArtifactStoredLifecycleAppender(
            jdbcTemplate, auditRepository, new EvidenceArtifactAuditPayloadCodec(
                JsonMapper.builder().findAndAddModules().build()
            )
        );
        UUID organizationId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID artifactId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID attemptId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        Instant occurredAt = Instant.parse("2030-01-01T00:00:00Z");
        EvidenceArtifactMetadata metadata = new EvidenceArtifactMetadata(
            artifactId, organizationId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "metric", "prometheus:synthetic/opsmind-api", "v1", "redacted-metrics",
            EvidenceArtifactDigest.parse("sha256:" + "a".repeat(64)), 4L, 7L, "evidence-90d", "singapore",
            "delete-within-24h", EvidenceArtifactLifecycleState.PENDING_UPLOAD, 1L, occurredAt.minusSeconds(1)
        );
        EvidenceArtifactUploadClaim claim = new EvidenceArtifactUploadClaim(
            metadata, "artifacts/v1/internal", attemptId, 1, occurredAt.plusSeconds(30), false
        );
        EvidenceArtifactUploadSettlement settlement = new EvidenceArtifactUploadSettlement(
            true, EvidenceArtifactLifecycleState.STORED, 2L, 1L, occurredAt
        );

        appender.append(claim, settlement);

        InOrder order = inOrder(jdbcTemplate, auditRepository);
        order.verify(jdbcTemplate).update(contains("upload_attempt_id"), any(Object[].class));
        order.verify(auditRepository).append(any());
        ArgumentCaptor<Object[]> eventArguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("upload_attempt_id"), eventArguments.capture());
        assertThat(eventArguments.getValue()[12]).isEqualTo(attemptId);
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRepository).append(audit.capture());
        assertThat(audit.getValue().eventId()).isEqualTo(EvidenceArtifactIdentity.lifecycleEventId(
            organizationId, artifactId, 2L, attemptId
        ));
        assertThat(audit.getValue().payloadJson()).doesNotContain("storageKey", "encryption", "objectUrl");
    }
}
