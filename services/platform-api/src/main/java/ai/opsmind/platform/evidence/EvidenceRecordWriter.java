package ai.opsmind.platform.evidence;

import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.UUID;

import ai.opsmind.platform.investigation.domain.InvestigationEvent;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes one evidence record inside the investigation event transaction. */
@Component
@Profile("persistence")
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "store", havingValue = "postgres")
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public final class EvidenceRecordWriter {

    private final JdbcTemplate jdbcTemplate;
    private final EvidenceContentCanonicalizer canonicalizer;

    public EvidenceRecordWriter(
        JdbcTemplate jdbcTemplate,
        EvidenceContentCanonicalizer canonicalizer
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.canonicalizer = canonicalizer;
    }

    public void append(
        InvestigationStateMachine.State state,
        UUID investigationEventId,
        InvestigationEvent.EvidenceAppended event
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Evidence append requires the investigation transaction.");
        }
        CollectedEvidence evidence = requireEvidence(state, event);
        canonicalizer.verify(evidence.canonicalContent(), evidence.contentDigest());
        jdbcTemplate.update(
            "INSERT INTO evidence_records (evidence_id, organization_id, project_id, incident_id, "
                + "run_id, actor_id, intent_id, execution_id, investigation_event_id, "
                + "gateway_audit_event_id, gateway_request_digest, source_type, source_identity, "
                + "target_identity, observed_at, window_start, window_end, connector_version, "
                + "manifest_version, policy_version, source_provenance, trust_class, content_digest, "
                + "canonical_content, redacted_fields, truncated, gateway_duplicate, "
                + "retention_class, lifecycle_state, "
                + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "?, ?, ?, ?, ?, ?, ?, 'evidence-90d', 'AVAILABLE', ?)",
            event.evidenceId(), state.organizationId(), state.projectId(), state.incidentId(),
            state.runId(), state.actorId(), event.intentId(), evidence.executionId(),
            investigationEventId, evidence.gatewayAuditEventId(), hex(evidence.gatewayRequestDigest()),
            evidence.sourceType(), evidence.source(), evidence.targetIdentity(),
            Timestamp.from(evidence.observedAt()), Timestamp.from(evidence.windowStart()),
            Timestamp.from(evidence.windowEnd()), evidence.connectorVersion(), evidence.manifestVersion(),
            evidence.policyVersion(), evidence.sourceProvenance(), evidence.trustClass(),
            hex(evidence.contentDigest().substring("sha256:".length())), evidence.canonicalContent(),
            evidence.redactedFields(), evidence.truncated(), evidence.gatewayDuplicate(),
            Timestamp.from(event.occurredAt())
        );
    }

    public boolean matchesExact(
        InvestigationStateMachine.State state,
        UUID investigationEventId,
        InvestigationEvent.EvidenceAppended event
    ) {
        CollectedEvidence evidence;
        try {
            evidence = requireEvidence(state, event);
            canonicalizer.verify(evidence.canonicalContent(), evidence.contentDigest());
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
        Boolean matches = jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM evidence_records WHERE evidence_id = ? "
                + "AND organization_id = ? AND project_id = ? AND incident_id = ? AND run_id = ? "
                + "AND actor_id = ? AND intent_id = ? AND execution_id = ? "
                + "AND investigation_event_id = ? AND gateway_audit_event_id = ? "
                + "AND gateway_request_digest = ? AND source_type = ? AND source_identity = ? "
                + "AND target_identity = ? AND observed_at = ? AND window_start = ? AND window_end = ? "
                + "AND connector_version = ? AND manifest_version = ? AND policy_version = ? "
                + "AND source_provenance = ? AND trust_class = ? AND content_digest = ? "
                + "AND canonical_content = ? AND redacted_fields = ? AND truncated = ? "
                + "AND gateway_duplicate = ? AND retention_class = 'evidence-90d' "
                + "AND lifecycle_state = 'AVAILABLE' AND created_at = ?)",
            Boolean.class, event.evidenceId(), state.organizationId(), state.projectId(),
            state.incidentId(), state.runId(), state.actorId(), event.intentId(), evidence.executionId(),
            investigationEventId, evidence.gatewayAuditEventId(), hex(evidence.gatewayRequestDigest()),
            evidence.sourceType(), evidence.source(), evidence.targetIdentity(),
            Timestamp.from(evidence.observedAt()), Timestamp.from(evidence.windowStart()),
            Timestamp.from(evidence.windowEnd()), evidence.connectorVersion(), evidence.manifestVersion(),
            evidence.policyVersion(), evidence.sourceProvenance(), evidence.trustClass(),
            hex(evidence.contentDigest().substring("sha256:".length())), evidence.canonicalContent(),
            evidence.redactedFields(), evidence.truncated(), evidence.gatewayDuplicate(),
            Timestamp.from(event.occurredAt())
        );
        return Boolean.TRUE.equals(matches);
    }

    private CollectedEvidence requireEvidence(
        InvestigationStateMachine.State state,
        InvestigationEvent.EvidenceAppended event
    ) {
        CollectedEvidence evidence = event.collectedEvidence();
        if (evidence == null
            || !event.evidenceId().equals(EvidenceIdentity.evidenceId(
                state.organizationId(), state.runId(), event.intentId()
            ))
            || !evidence.executionId().equals(EvidenceIdentity.executionId(
                state.organizationId(), state.runId(), event.intentId()
            ))) {
            throw new IllegalArgumentException("Evidence identity does not match its run and intent.");
        }
        return evidence;
    }

    private byte[] hex(String value) {
        try {
            return HexFormat.of().parseHex(value);
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Evidence digest is not lowercase hexadecimal.", exception);
        }
    }
}
