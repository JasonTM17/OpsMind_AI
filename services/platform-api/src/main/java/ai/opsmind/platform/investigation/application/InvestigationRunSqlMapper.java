package ai.opsmind.platform.investigation.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

import org.springframework.stereotype.Component;

@Component
final class InvestigationRunSqlMapper {

    static final String STATE_COLUMNS =
        "run_id, organization_id, project_id, incident_id, actor_id, status, "
            + "max_rounds, max_tool_calls, max_evidence_items, max_tokens, revision, "
            + "event_count, rounds, tool_calls, total_tokens, "
            + "requested_fingerprints_state::text, evidence_ids_state::text, "
            + "pending_intents_state::text, final_response::text, terminal_reason, "
            + "started_at, deadline_at, ended_at";

    private final InvestigationPersistenceJsonCodec jsonCodec;

    InvestigationRunSqlMapper(InvestigationPersistenceJsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    Object[] insertParameters(InvestigationStateMachine.State state) {
        InvestigationCommand.Budget budget = state.budget();
        return new Object[] {
            state.runId(), state.organizationId(), state.projectId(), state.incidentId(), state.actorId(),
            state.status().name(), budget.maxRounds(), budget.maxToolCalls(), budget.maxEvidenceItems(),
            budget.maxTokens(), state.revision(), state.eventCount(), state.rounds(), state.toolCalls(),
            state.totalTokens(), jsonCodec.write(state.requestedFingerprints()),
            jsonCodec.write(state.evidenceIds()), jsonCodec.write(state.pendingIntents()),
            nullableJson(state.finalResponse()), state.terminalReason(), Timestamp.from(state.startedAt()),
            Timestamp.from(state.deadlineAt()), timestamp(state.endedAt())
        };
    }

    InvestigationStateMachine.State mapState(ResultSet row, int rowNumber) throws SQLException {
        return new InvestigationStateMachine.State(
            row.getObject("run_id", UUID.class), row.getObject("organization_id", UUID.class),
            row.getObject("project_id", UUID.class), row.getObject("incident_id", UUID.class),
            row.getObject("actor_id", UUID.class), new InvestigationCommand.Budget(
                row.getInt("max_rounds"), row.getInt("max_tool_calls"),
                row.getInt("max_evidence_items"), row.getInt("max_tokens")
            ), InvestigationStateMachine.Status.valueOf(row.getString("status")),
            row.getLong("revision"), row.getLong("event_count"), row.getInt("rounds"),
            row.getInt("tool_calls"), row.getInt("total_tokens"),
            jsonCodec.readFingerprints(row.getString("requested_fingerprints_state")),
            jsonCodec.readEvidenceIds(row.getString("evidence_ids_state")),
            jsonCodec.readToolIntents(row.getString("pending_intents_state")),
            jsonCodec.readFinalResponse(row.getString("final_response")), row.getString("terminal_reason"),
            row.getTimestamp("started_at").toInstant(), row.getTimestamp("deadline_at").toInstant(),
            instant(row.getTimestamp("ended_at"))
        );
    }

    String nullableJson(Object value) {
        return value == null ? null : jsonCodec.write(value);
    }

    Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
