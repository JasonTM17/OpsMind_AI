package ai.opsmind.platform.investigation.workflow;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import ai.opsmind.platform.messaging.EventEnvelope;
import ai.opsmind.platform.messaging.InboxRepository;
import ai.opsmind.platform.messaging.OutboxDispatcherTenantContextSql;
import ai.opsmind.platform.messaging.OutboxLease;
import ai.opsmind.platform.messaging.OutboxLeaseRepository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
    prefix = "opsmind.investigation.workflow-starter",
    name = "enabled",
    havingValue = "true"
)
public final class InvestigationWorkflowDispatchTransactions {

    public static final String CONSUMER = "investigation-workflow-starter-v1";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final OutboxDispatcherTenantContextSql tenantContext;
    private final OutboxLeaseRepository outbox;
    private final InboxRepository inbox;

    public InvestigationWorkflowDispatchTransactions(
        @Qualifier("dispatcherJdbcTemplate") JdbcTemplate jdbcTemplate,
        @Qualifier("dispatcherTransactionManager") PlatformTransactionManager transactionManager,
        OutboxDispatcherTenantContextSql tenantContext,
        OutboxLeaseRepository outbox,
        @Qualifier("dispatcherInboxRepository") InboxRepository inbox
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.tenantContext = tenantContext;
        this.outbox = outbox;
        this.inbox = inbox;
    }

    public List<OutboxLease> claim(
        UUID organizationId,
        UUID leaseToken,
        Instant now,
        InvestigationWorkflowStarterProperties properties
    ) {
        return inTenant(organizationId, () -> outbox.claimBatchForEventType(
            organizationId,
            leaseToken,
            now,
            properties.leaseDuration(),
            properties.batchSize(),
            InvestigationWorkflowStartEnvelopeFactory.EVENT_TYPE
        ));
    }

    public boolean leaseIsLive(OutboxLease lease) {
        return inTenant(lease.event().organizationId(), () -> Boolean.TRUE.equals(
            jdbcTemplate.queryForObject(
                "SELECT EXISTS ("
                    + "SELECT 1 FROM outbox_events event "
                    + "JOIN investigation_workflow_bindings binding "
                    + "ON binding.organization_id = event.organization_id "
                    + "AND binding.run_id = event.aggregate_id "
                    + "AND binding.start_event_id = event.event_id "
                    + "WHERE event.organization_id = ? AND event.event_id = ? "
                    + "AND event.lease_token = ? "
                    + "AND event.lease_expires_at > transaction_timestamp() "
                    + "AND event.published_at IS NULL AND event.poisoned_at IS NULL "
                    + "AND binding.status = 'PENDING'"
                    + ")",
                Boolean.class,
                lease.event().organizationId(),
                lease.event().eventId(),
                lease.leaseToken()
            )
        ));
    }

    public void acknowledgeStarted(
        OutboxLease lease,
        String temporalRunId,
        Instant acknowledgedAt
    ) {
        EventEnvelope event = lease.event();
        inTenant(event.organizationId(), () -> {
            requireInboxClaim(lease);
            int bindingUpdated = jdbcTemplate.update(
                "UPDATE investigation_workflow_bindings "
                    + "SET status = 'STARTED', temporal_run_id = ?, temporal_started_at = ?, "
                    + "updated_at = ? WHERE organization_id = ? AND run_id = ? "
                    + "AND start_event_id = ? AND status = 'PENDING'",
                temporalRunId,
                Timestamp.from(acknowledgedAt),
                Timestamp.from(acknowledgedAt),
                event.organizationId(),
                event.aggregateId(),
                event.eventId()
            );
            if (bindingUpdated != 1
                || !inbox.markProcessed(
                    event.organizationId(), event.eventId(), CONSUMER
                )
                || !outbox.markPublished(
                    event.organizationId(),
                    event.eventId(),
                    lease.leaseToken(),
                    acknowledgedAt
                )) {
                throw new IllegalStateException("Workflow start acknowledgement lost its lease.");
            }
            return true;
        });
    }

    public boolean releaseRetry(
        OutboxLease lease,
        String errorCode,
        Instant failedAt,
        Instant retryAt
    ) {
        return inTenant(lease.event().organizationId(), () -> outbox.releaseAfterFailure(
            lease.event().organizationId(),
            lease.event().eventId(),
            lease.leaseToken(),
            errorCode,
            failedAt,
            retryAt,
            false
        ));
    }

    public void reject(
        OutboxLease lease,
        String errorCode,
        Instant rejectedAt
    ) {
        EventEnvelope event = lease.event();
        inTenant(event.organizationId(), () -> {
            requireInboxClaim(lease);
            int bindingUpdated = jdbcTemplate.update(
                "UPDATE investigation_workflow_bindings "
                    + "SET status = 'REJECTED', rejection_code = ?, rejected_at = ?, updated_at = ? "
                    + "WHERE organization_id = ? AND run_id = ? "
                    + "AND start_event_id = ? AND status = 'PENDING'",
                errorCode,
                Timestamp.from(rejectedAt),
                Timestamp.from(rejectedAt),
                event.organizationId(),
                event.aggregateId(),
                event.eventId()
            );
            if (bindingUpdated != 1
                || !inbox.markPoisoned(
                    event.organizationId(), event.eventId(), CONSUMER, errorCode
                )
                || !outbox.releaseAfterFailure(
                    event.organizationId(),
                    event.eventId(),
                    lease.leaseToken(),
                    errorCode,
                    rejectedAt,
                    rejectedAt,
                    true
                )) {
                throw new IllegalStateException("Workflow start rejection lost its lease.");
            }
            return true;
        });
    }

    private void requireInboxClaim(OutboxLease lease) {
        EventEnvelope event = lease.event();
        if (!inbox.claim(
            event.organizationId(), event.eventId(), CONSUMER
        )) {
            throw new IllegalStateException("Workflow start inbox claim is not available.");
        }
    }

    private <T> T inTenant(UUID organizationId, Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> {
            tenantContext.apply(organizationId);
            return work.get();
        }));
    }
}
