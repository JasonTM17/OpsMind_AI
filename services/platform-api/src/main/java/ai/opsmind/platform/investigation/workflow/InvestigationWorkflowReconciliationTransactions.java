package ai.opsmind.platform.investigation.workflow;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
    prefix = "opsmind.investigation.workflow-reconciler",
    name = "enabled",
    havingValue = "true"
)
public final class InvestigationWorkflowReconciliationTransactions {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final InvestigationTemporalObserverProperties observerProperties;

    public InvestigationWorkflowReconciliationTransactions(
        @Qualifier("workflowReconcilerJdbcTemplate") JdbcTemplate jdbcTemplate,
        @Qualifier("workflowReconcilerTransactionManager")
        PlatformTransactionManager transactionManager,
        InvestigationTemporalObserverProperties observerProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.observerProperties = observerProperties;
    }

    public Optional<InvestigationWorkflowReconciliationLease> claim(
        UUID leaseToken,
        InvestigationWorkflowReconcilerProperties properties
    ) {
        properties.validate(observerProperties.getRpcTimeout());
        var rows = inTransaction(() -> jdbcTemplate.query(
            "SELECT event_id, organization_id, aggregate_type, aggregate_id, "
                + "aggregate_sequence, event_type, schema_version, causation_id, "
                + "correlation_id, occurred_at, payload_bytes, payload_digest, "
                + "lease_token, lease_expires_at, outbox_attempts, temporal_cluster_id, "
                + "temporal_namespace, workflow_id, workflow_type, task_queue, "
                + "start_payload_digest, reconciliation_attempt, reconciliation_received_at, "
                + "reconciliation_last_code, reconciliation_last_observed_at "
                + "FROM public.opsmind_claim_investigation_workflow_reconciliation(?, ?, ?, ?)",
            InvestigationWorkflowReconciliationLeaseRowMapper.INSTANCE,
            leaseToken,
            millis(properties.leaseDuration()),
            properties.maximumAttempts(),
            millis(properties.maximumAge())
        ));
        if (rows.size() > 1) {
            throw new IllegalStateException(
                "Workflow reconciler must not claim more than one row."
            );
        }
        return rows.stream().findFirst();
    }

    public InvestigationWorkflowReconciliationSettlementResult settle(
        InvestigationWorkflowReconciliationLease lease,
        InvestigationWorkflowObservation observation,
        Duration retryDelay,
        InvestigationWorkflowReconcilerProperties properties
    ) {
        String code = inTransaction(() -> jdbcTemplate.queryForObject(
            "SELECT public.opsmind_settle_investigation_workflow_reconciliation("
                + "?, ?, ?, ?, ?, ?, ?, ?, ?)",
            String.class,
            lease.event().organizationId(),
            lease.event().eventId(),
            lease.leaseToken(),
            observation.outcome().name(),
            observation.firstRunId(),
            observation.safeCode(),
            retryDelay == null ? null : millis(retryDelay),
            millis(properties.absenceConfirmationDelay()),
            millis(properties.maximumVerifiableAge())
        ));
        if (code == null || code.isBlank()) {
            throw new IllegalStateException(
                "Workflow reconciliation settlement returned no result."
            );
        }
        return InvestigationWorkflowReconciliationSettlementResult.fromCode(code);
    }

    public InvestigationWorkflowReconciliationStatus status() {
        return inTransaction(() -> jdbcTemplate.queryForObject(
            "SELECT claim_ready_count, pending_count, blocked_count, exhausted_count, "
                + "retention_ineligible_count, oldest_pending_age_seconds "
                + "FROM public.opsmind_get_investigation_workflow_reconciliation_status()",
            (resultSet, rowNumber) -> new InvestigationWorkflowReconciliationStatus(
                resultSet.getLong("claim_ready_count"),
                resultSet.getLong("pending_count"),
                resultSet.getLong("blocked_count"),
                resultSet.getLong("exhausted_count"),
                resultSet.getLong("retention_ineligible_count"),
                resultSet.getDouble("oldest_pending_age_seconds")
            )
        ));
    }

    private static long millis(Duration duration) {
        long value = duration.toMillis();
        if (value < 1) {
            throw new IllegalArgumentException("Reconciliation duration is invalid.");
        }
        return value;
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }
}
