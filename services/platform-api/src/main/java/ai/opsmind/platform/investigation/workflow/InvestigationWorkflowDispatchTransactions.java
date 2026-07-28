package ai.opsmind.platform.investigation.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import ai.opsmind.platform.messaging.EventEnvelope;
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

    public InvestigationWorkflowDispatchTransactions(
        @Qualifier("dispatcherJdbcTemplate") JdbcTemplate jdbcTemplate,
        @Qualifier("dispatcherTransactionManager") PlatformTransactionManager transactionManager,
        OutboxDispatcherTenantContextSql tenantContext,
        OutboxLeaseRepository outbox
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.tenantContext = tenantContext;
        this.outbox = outbox;
    }

    public Optional<OutboxLease> claim(
        UUID organizationId,
        UUID leaseToken,
        Instant now,
        InvestigationWorkflowStarterProperties properties
    ) {
        return inTenant(organizationId, () -> {
            var claimed = outbox.claimBatchForEventType(
                organizationId,
                leaseToken,
                now,
                properties.leaseDuration(),
                properties.batchSize(),
                InvestigationWorkflowStartEnvelopeFactory.EVENT_TYPE
            );
            if (claimed.size() > 1) {
                throw new IllegalStateException(
                    "Workflow starter must not claim more than one lease per transaction."
                );
            }
            return claimed.stream().findFirst();
        });
    }

    public InvestigationWorkflowDispatchPreflightDecision preflight(
        OutboxLease lease,
        Duration requiredRpcWindow
    ) {
        EventEnvelope event = requireLease(lease).event();
        long requiredWindowMillis = requirePositiveMillis(
            requiredRpcWindow,
            "Workflow required RPC window is invalid."
        );
        String decisionCode = inDispatcher(() -> jdbcTemplate.queryForObject(
            "SELECT public.opsmind_preflight_investigation_workflow_start(?, ?, ?, ?)",
            String.class,
            event.organizationId(),
            event.eventId(),
            lease.leaseToken(),
            requiredWindowMillis
        ));
        if (decisionCode == null || decisionCode.isBlank()) {
            throw new IllegalStateException("Workflow dispatch preflight returned no decision.");
        }
        return InvestigationWorkflowDispatchPreflightDecision.fromCode(decisionCode);
    }

    public InvestigationWorkflowDispatchSettlementResult acknowledgeStarted(
        OutboxLease lease,
        String temporalRunId
    ) {
        if (temporalRunId == null || temporalRunId.isBlank()) {
            throw new IllegalArgumentException("Temporal run identity is required for settlement.");
        }
        return settle(lease, "STARTED", temporalRunId, null, null);
    }

    public InvestigationWorkflowDispatchSettlementResult releaseRetry(
        OutboxLease lease,
        String errorCode,
        Duration retryDelay
    ) {
        long retryDelayMillis = requirePositiveMillis(
            retryDelay,
            "Workflow retry delay is invalid."
        );
        return settle(lease, "RETRY", null, requireErrorCode(errorCode), retryDelayMillis);
    }

    public InvestigationWorkflowDispatchSettlementResult reject(
        OutboxLease lease,
        String errorCode
    ) {
        return settle(lease, "REJECTED", null, requireErrorCode(errorCode), null);
    }

    public int terminalizeUnclaimedIneligible(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Workflow ineligible terminalizer limit is invalid.");
        }
        Integer terminalized = inDispatcher(() -> jdbcTemplate.queryForObject(
            "SELECT public.opsmind_terminalize_unclaimed_ineligible_workflow_starts(?)",
            Integer.class,
            limit
        ));
        return Objects.requireNonNull(
            terminalized,
            "Workflow ineligible terminalizer returned no result."
        );
    }

    private InvestigationWorkflowDispatchSettlementResult settle(
        OutboxLease lease,
        String outcome,
        String temporalRunId,
        String errorCode,
        Long retryDelayMillis
    ) {
        EventEnvelope event = requireLease(lease).event();
        String resultCode = inDispatcher(() -> jdbcTemplate.queryForObject(
            "SELECT public.opsmind_settle_investigation_workflow_start(?, ?, ?, ?, ?, ?, ?)",
            String.class,
            event.organizationId(),
            event.eventId(),
            lease.leaseToken(),
            outcome,
            temporalRunId,
            errorCode,
            retryDelayMillis
        ));
        if (resultCode == null || resultCode.isBlank()) {
            throw new IllegalStateException("Workflow dispatch settlement returned no result.");
        }
        return InvestigationWorkflowDispatchSettlementResult.fromCode(resultCode);
    }

    private static OutboxLease requireLease(OutboxLease lease) {
        if (lease == null) {
            throw new IllegalArgumentException("Workflow dispatch lease is required.");
        }
        return lease;
    }

    private static String requireErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("Workflow dispatch error code is required.");
        }
        return errorCode;
    }

    private static long requirePositiveMillis(Duration duration, String message) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(message);
        }
        long milliseconds = duration.toMillis();
        if (milliseconds < 1) {
            throw new IllegalArgumentException(message);
        }
        return milliseconds;
    }

    private <T> T inTenant(UUID organizationId, Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> {
            tenantContext.apply(organizationId);
            return work.get();
        }));
    }

    private <T> T inDispatcher(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }
}
