package ai.opsmind.platform.investigation.application;

import java.sql.SQLException;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.tenancy.TenantContextSql;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("persistence")
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "store", havingValue = "postgres")
public final class JdbcInvestigationRunStore implements InvestigationRunStore {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContextSql tenantContextSql;
    private final TransactionTemplate transactions;
    private final InvestigationPersistenceJsonCodec jsonCodec;
    private final InvestigationRunSqlMapper sqlMapper;
    private final InvestigationEventLedger eventLedger;

    public JdbcInvestigationRunStore(
        JdbcTemplate jdbcTemplate,
        TenantContextSql tenantContextSql,
        PlatformTransactionManager transactionManager,
        InvestigationPersistenceJsonCodec jsonCodec,
        InvestigationRunSqlMapper sqlMapper,
        InvestigationEventLedger eventLedger
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContextSql = tenantContextSql;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jsonCodec = jsonCodec;
        this.sqlMapper = sqlMapper;
        this.eventLedger = eventLedger;
    }

    @Override
    public void create(InvestigationStateMachine.Step initial) {
        InvestigationStateMachine.State state = initial.state();
        if (state.revision() != 0 || state.eventCount() != initial.events().size()) {
            throw new IllegalArgumentException("Initial investigation persistence version is invalid.");
        }
        execute(state.organizationId(), state.actorId(), () -> {
            jdbcTemplate.update(
                "INSERT INTO investigation_runs (run_id, organization_id, project_id, incident_id, "
                    + "actor_id, status, max_rounds, max_tool_calls, max_evidence_items, max_tokens, "
                    + "revision, event_count, rounds, tool_calls, total_tokens, "
                    + "requested_fingerprints_state, evidence_ids_state, pending_intents_state, "
                    + "final_response, terminal_reason, started_at, deadline_at, ended_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), "
                    + "CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?)",
                sqlMapper.insertParameters(state)
            );
            eventLedger.append(state, initial.events(), 1);
            return null;
        });
    }

    @Override
    public void save(
        InvestigationStateMachine.State previous,
        InvestigationStateMachine.Step next
    ) {
        InvestigationStateMachine.State state = next.state();
        requireSuccessor(previous, state, next.events().size());
        execute(state.organizationId(), state.actorId(), () -> {
            int updated = jdbcTemplate.update(
                "UPDATE investigation_runs SET status = ?, revision = ?, event_count = ?, rounds = ?, "
                    + "tool_calls = ?, total_tokens = ?, requested_fingerprints_state = CAST(? AS jsonb), "
                    + "evidence_ids_state = CAST(? AS jsonb), pending_intents_state = CAST(? AS jsonb), "
                    + "final_response = CAST(? AS jsonb), terminal_reason = ?, ended_at = ? "
                    + "WHERE organization_id = ? AND run_id = ? AND revision = ? AND event_count = ?",
                state.status().name(), state.revision(), state.eventCount(), state.rounds(),
                state.toolCalls(), state.totalTokens(), jsonCodec.write(state.requestedFingerprints()),
                jsonCodec.write(state.evidenceIds()), jsonCodec.write(state.pendingIntents()),
                sqlMapper.nullableJson(state.finalResponse()), state.terminalReason(),
                sqlMapper.timestamp(state.endedAt()),
                state.organizationId(), state.runId(), previous.revision(), previous.eventCount()
            );
            if (updated != 1) throw conflict(null);
            eventLedger.append(state, next.events(), previous.eventCount() + 1);
            return null;
        });
    }

    @Override
    public InvestigationStateMachine.State require(UUID organizationId, UUID actorId, UUID runId) {
        return execute(organizationId, actorId, () -> {
            try {
                return jdbcTemplate.queryForObject(
                    "SELECT run_id, organization_id, project_id, incident_id, actor_id, status, "
                        + "max_rounds, max_tool_calls, max_evidence_items, max_tokens, revision, "
                        + "event_count, rounds, tool_calls, total_tokens, "
                        + "requested_fingerprints_state::text, evidence_ids_state::text, "
                        + "pending_intents_state::text, final_response::text, terminal_reason, "
                        + "started_at, deadline_at, ended_at FROM investigation_runs "
                        + "WHERE organization_id = ? AND run_id = ?",
                    sqlMapper::mapState,
                    organizationId,
                    runId
                );
            }
            catch (EmptyResultDataAccessException exception) {
                throw notFound();
            }
        });
    }

    private <T> T execute(UUID organizationId, UUID actorId, Work<T> work) {
        try {
            T result = transactions.execute(status -> {
                tenantContextSql.apply(organizationId, actorId);
                return work.run();
            });
            return result;
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (DataAccessException exception) {
            if (hasSqlState(exception, "23505") || hasSqlState(exception, "P7002")) {
                throw conflict(exception);
            }
            throw unavailable(exception);
        }
    }

    private void requireSuccessor(
        InvestigationStateMachine.State previous,
        InvestigationStateMachine.State next,
        int emittedEvents
    ) {
        if (!previous.runId().equals(next.runId())
            || !previous.organizationId().equals(next.organizationId())
            || !previous.actorId().equals(next.actorId())
            || next.revision() != previous.revision() + 1
            || next.eventCount() != previous.eventCount() + emittedEvents
            || emittedEvents < 1) {
            throw new IllegalArgumentException("Investigation persistence successor is invalid.");
        }
    }

    private PlatformProblemException conflict(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.CONFLICT, "investigation.run-conflict",
            "The investigation run changed before it could be persisted.", cause
        );
    }

    private PlatformProblemException notFound() {
        return new PlatformProblemException(
            HttpStatus.NOT_FOUND, "investigation.run-not-found", "The investigation run was not found."
        );
    }

    private PlatformProblemException unavailable(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE, "investigation.persistence-unavailable",
            "Investigation persistence is temporarily unavailable.", cause
        );
    }

    private boolean hasSqlState(Throwable throwable, String expected) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && expected.equals(sql.getSQLState())) return true;
        }
        return false;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run();
    }
}
