package ai.opsmind.platform.investigation.workflow;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import ai.opsmind.platform.common.api.RequestDigest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(
    prefix = "opsmind.investigation.workflow-reconciler",
    name = "enabled",
    havingValue = "true"
)
public final class InvestigationWorkflowReconciler {

    private final InvestigationWorkflowReconciliationTransactions transactions;
    private final InvestigationWorkflowStartEventCodec eventCodec;
    private final InvestigationWorkflowObserver observer;
    private final InvestigationWorkflowReconcilerProperties properties;
    private final InvestigationTemporalObserverProperties observerProperties;
    private final InvestigationWorkflowReconciliationMetrics metrics;
    private final Clock clock;

    public InvestigationWorkflowReconciler(
        InvestigationWorkflowReconciliationTransactions transactions,
        InvestigationWorkflowStartEventCodec eventCodec,
        InvestigationWorkflowObserver observer,
        InvestigationWorkflowReconcilerProperties properties,
        InvestigationTemporalObserverProperties observerProperties,
        InvestigationWorkflowReconciliationMetrics metrics,
        Clock clock
    ) {
        this.transactions = transactions;
        this.eventCodec = eventCodec;
        this.observer = observer;
        this.properties = properties;
        this.observerProperties = observerProperties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public int reconcileOne() {
        properties.validate(observerProperties.getRpcTimeout());
        var claim = transactions.claim(UUID.randomUUID(), properties);
        metrics.updateDatabaseReady(true);
        return claim
            .filter(this::process)
            .map(ignored -> 1)
            .orElse(0);
    }

    public void refreshStatus() {
        metrics.updateStatus(transactions.status());
    }

    public void markDatabaseUnavailable() {
        metrics.updateDatabaseReady(false);
    }

    private boolean process(InvestigationWorkflowReconciliationLease lease) {
        Instant now = Instant.now(clock);
        InvestigationWorkflowObservation localBlock = preflight(lease, now);
        if (localBlock != null) {
            return settle(lease, localBlock, null, now);
        }

        InvestigationWorkflowStartEventCodec.DecodedStart decoded;
        try {
            decoded = decodeExactContract(lease);
        }
        catch (RuntimeException failure) {
            return settle(
                lease,
                InvestigationWorkflowObservation.blocked(
                    "workflow.reconciliation-decode-failed"
                ),
                null,
                now
            );
        }

        requireNoDatabaseTransaction();
        InvestigationWorkflowObservation observed;
        try {
            observed = observer.observeExactWorkflow(
                decoded.request(), decoded.startPayloadDigest()
            );
        }
        catch (RuntimeException failure) {
            observed = InvestigationWorkflowObservation.blocked(
                "workflow.reconciliation-observer-failed"
            );
        }
        Duration retryDelay = null;
        if (observed.outcome() == InvestigationWorkflowObservation.Outcome.RETRY) {
            retryDelay = properties.retryDelay(lease.reconciliationAttempt());
            if (lease.reconciliationAttempt() >= properties.maximumAttempts()
                || !now.plus(retryDelay).isBefore(
                    lease.reconciliationReceivedAt().plus(properties.maximumAge())
                )) {
                observed = InvestigationWorkflowObservation.blocked(
                    "workflow.reconciliation-exhausted"
                );
                retryDelay = null;
            }
        }
        return settle(lease, observed, retryDelay, Instant.now(clock));
    }

    private InvestigationWorkflowObservation preflight(
        InvestigationWorkflowReconciliationLease lease,
        Instant now
    ) {
        if (!now.isBefore(
            lease.event().occurredAt().plus(properties.maximumVerifiableAge())
        )) {
            return InvestigationWorkflowObservation.blocked(
                "workflow.reconciliation-retention-unverifiable"
            );
        }
        if (lease.reconciliationAttempt() > properties.maximumAttempts()
            || !now.isBefore(
                lease.reconciliationReceivedAt().plus(properties.maximumAge())
            )) {
            return InvestigationWorkflowObservation.blocked(
                "workflow.reconciliation-exhausted"
            );
        }
        return null;
    }

    private InvestigationWorkflowStartEventCodec.DecodedStart decodeExactContract(
        InvestigationWorkflowReconciliationLease lease
    ) {
        byte[] payloadBytes = lease.event().payloadJson().getBytes(StandardCharsets.UTF_8);
        byte[] actualDigest = RequestDigest.sha256(payloadBytes);
        if (!RequestDigest.constantTimeEquals(actualDigest, lease.event().payloadDigest())
            || !RequestDigest.constantTimeEquals(
                lease.event().payloadDigest(), lease.startPayloadDigest()
            )) {
            throw new IllegalArgumentException("Workflow event digest is invalid.");
        }
        var decoded = eventCodec.decode(lease.event());
        var request = decoded.request();
        if (!lease.temporalClusterId().equals(request.temporalClusterId())
            || !lease.temporalNamespace().equals(request.temporalNamespace())
            || !lease.workflowId().equals(request.workflowId())
            || !lease.workflowType().equals(request.workflowType())
            || !lease.taskQueue().equals(request.taskQueue())
            || !decoded.startPayloadDigest().equals(
                HexFormat.of().formatHex(lease.startPayloadDigest())
            )) {
            throw new IllegalArgumentException("Workflow binding target is invalid.");
        }
        return decoded;
    }

    private boolean settle(
        InvestigationWorkflowReconciliationLease lease,
        InvestigationWorkflowObservation observation,
        Duration retryDelay,
        Instant observedAt
    ) {
        var result = transactions.settle(lease, observation, retryDelay, properties);
        String metricOutcome = "workflow.reconciliation-exhausted".equals(
            observation.safeCode()
        ) ? "exhausted" : result.metricOutcome();
        metrics.recordOutcome(
            metricOutcome,
            Duration.between(lease.event().occurredAt(), observedAt)
        );
        return result.handled();
    }

    private static void requireNoDatabaseTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "Temporal observation cannot run inside a database transaction."
            );
        }
    }
}
