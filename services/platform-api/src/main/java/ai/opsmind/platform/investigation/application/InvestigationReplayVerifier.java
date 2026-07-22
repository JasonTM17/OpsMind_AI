package ai.opsmind.platform.investigation.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import ai.opsmind.platform.evidence.CollectedEvidence;
import ai.opsmind.platform.evidence.EvidenceRecordWriter;
import ai.opsmind.platform.investigation.domain.InvestigationEvent;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Accepts only a byte-for-byte logical replay of an already committed transition. */
@Component
@Profile("persistence")
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "store", havingValue = "postgres")
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
final class InvestigationReplayVerifier {

    private final JdbcTemplate jdbcTemplate;
    private final InvestigationRunSqlMapper sqlMapper;
    private final InvestigationPersistenceJsonCodec jsonCodec;
    private final EvidenceRecordWriter evidenceWriter;

    InvestigationReplayVerifier(
        JdbcTemplate jdbcTemplate,
        InvestigationRunSqlMapper sqlMapper,
        InvestigationPersistenceJsonCodec jsonCodec,
        EvidenceRecordWriter evidenceWriter
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqlMapper = sqlMapper;
        this.jsonCodec = jsonCodec;
        this.evidenceWriter = evidenceWriter;
    }

    boolean matches(
        InvestigationStateMachine.State previous,
        InvestigationStateMachine.Step next
    ) {
        List<InvestigationStateMachine.State> persisted = jdbcTemplate.query(
            "SELECT " + InvestigationRunSqlMapper.STATE_COLUMNS + " FROM investigation_runs "
                + "WHERE organization_id = ? AND run_id = ?",
            sqlMapper::mapState, previous.organizationId(), previous.runId()
        );
        if (persisted.size() != 1 || !sameState(persisted.get(0), next.state())) return false;

        long sequence = previous.eventCount() + 1;
        for (InvestigationEvent event : next.events()) {
            UUID eventId = InvestigationEventLedger.eventId(
                previous.organizationId(), previous.runId(), sequence
            );
            if (!matchesEvent(next.state(), event, eventId, sequence)) return false;
            if (event instanceof InvestigationEvent.EvidenceAppended evidence
                && !evidenceWriter.matchesExact(next.state(), eventId, evidence)) return false;
            sequence++;
        }
        return true;
    }

    private boolean matchesEvent(
        InvestigationStateMachine.State state,
        InvestigationEvent event,
        UUID eventId,
        long sequence
    ) {
        CollectedEvidence evidence = event instanceof InvestigationEvent.EvidenceAppended value
            ? value.collectedEvidence() : null;
        byte[] requestDigest = evidence == null ? null
            : HexFormat.of().parseHex(evidence.gatewayRequestDigest());
        String payload = jsonCodec.eventPayload(
            eventId, state.organizationId(), state.projectId(), state.incidentId(), state.runId(),
            sequence, state.actorId(), event
        );
        Boolean matches = jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM investigation_run_events WHERE event_id = ? "
                + "AND organization_id = ? AND project_id = ? AND incident_id = ? AND run_id = ? "
                + "AND sequence_no = ? AND event_type = ? AND actor_id = ? AND occurred_at = ? "
                + "AND payload = CAST(? AS jsonb) AND tool_execution_id IS NOT DISTINCT FROM ? "
                + "AND tool_request_digest IS NOT DISTINCT FROM ?)",
            Boolean.class, eventId, state.organizationId(), state.projectId(), state.incidentId(),
            state.runId(), sequence, jsonCodec.eventType(event), state.actorId(),
            Timestamp.from(jsonCodec.occurredAt(event)), payload,
            evidence == null ? null : evidence.executionId(), requestDigest
        );
        return Boolean.TRUE.equals(matches);
    }

    private boolean sameState(
        InvestigationStateMachine.State actual,
        InvestigationStateMachine.State expected
    ) {
        return actual.runId().equals(expected.runId())
            && actual.organizationId().equals(expected.organizationId())
            && actual.projectId().equals(expected.projectId())
            && actual.incidentId().equals(expected.incidentId())
            && actual.actorId().equals(expected.actorId())
            && actual.budget().equals(expected.budget())
            && actual.status() == expected.status()
            && actual.revision() == expected.revision()
            && actual.eventCount() == expected.eventCount()
            && actual.rounds() == expected.rounds()
            && actual.toolCalls() == expected.toolCalls()
            && actual.totalTokens() == expected.totalTokens()
            && actual.requestedFingerprints().equals(expected.requestedFingerprints())
            && actual.evidenceIds().equals(expected.evidenceIds())
            && actual.pendingIntents().equals(expected.pendingIntents())
            && Objects.equals(actual.finalResponse(), expected.finalResponse())
            && Objects.equals(actual.terminalReason(), expected.terminalReason())
            && sameInstant(actual.startedAt(), expected.startedAt())
            && sameInstant(actual.deadlineAt(), expected.deadlineAt())
            && sameInstant(actual.endedAt(), expected.endedAt());
    }

    private boolean sameInstant(Instant actual, Instant expected) {
        if (actual == null || expected == null) return actual == expected;
        return actual.truncatedTo(ChronoUnit.MICROS).equals(expected.truncatedTo(ChronoUnit.MICROS));
    }
}
