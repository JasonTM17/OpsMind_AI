package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class InvestigationWorkflowReconcilerTest {

    @Test
    void expiredLeaseCannotEmitAnExhaustedOutcome() {
        InvestigationWorkflowObservation exhausted =
            InvestigationWorkflowObservation.blocked(
                "workflow.reconciliation-exhausted"
            );

        assertThat(InvestigationWorkflowReconciler.metricOutcome(
            InvestigationWorkflowReconciliationSettlementResult.LEASE_LOST,
            exhausted
        )).isEqualTo("lease_lost");
        assertThat(InvestigationWorkflowReconciler.metricOutcome(
            InvestigationWorkflowReconciliationSettlementResult.BLOCKED,
            exhausted
        )).isEqualTo("exhausted");
    }

    @Test
    void handoffAgeIsAnEnforcedEligibilityBoundary() {
        InvestigationWorkflowReconcilerProperties properties = properties();
        Instant now = Instant.parse("2030-01-01T02:00:00Z");

        assertThat(InvestigationWorkflowReconciliationPreflightPolicy.safeCode(
            now.minus(Duration.ofHours(2)),
            now.minus(Duration.ofMinutes(1)),
            1,
            properties,
            now
        )).isEqualTo("workflow.reconciliation-handoff-age-exceeded");
    }

    @Test
    void aTimelyHandoffCanReconcileAfterTheHandoffBoundary() {
        InvestigationWorkflowReconcilerProperties properties = properties();
        Instant occurredAt = Instant.parse("2030-01-01T00:00:00Z");

        assertThat(InvestigationWorkflowReconciliationPreflightPolicy.safeCode(
            occurredAt,
            occurredAt.plus(Duration.ofMinutes(59)),
            2,
            properties,
            occurredAt.plus(Duration.ofMinutes(61))
        )).isNull();
    }

    @Test
    void eligibilityEqualityAndPrecedenceBoundariesRemainStable() {
        InvestigationWorkflowReconcilerProperties properties = properties();
        Instant occurredAt = Instant.parse("2030-01-01T00:00:00Z");
        Instant handoffAt = occurredAt.plus(properties.maximumHandoffAge());

        assertThat(InvestigationWorkflowReconciliationPreflightPolicy.safeCode(
            occurredAt,
            handoffAt,
            properties.maximumAttempts(),
            properties,
            handoffAt.plusSeconds(1)
        )).isNull();
        assertThat(InvestigationWorkflowReconciliationPreflightPolicy.safeCode(
            occurredAt,
            handoffAt,
            properties.maximumAttempts() + 1,
            properties,
            handoffAt.plusSeconds(1)
        )).isEqualTo("workflow.reconciliation-exhausted");
        assertThat(InvestigationWorkflowReconciliationPreflightPolicy.safeCode(
            occurredAt,
            handoffAt,
            1,
            properties,
            handoffAt.plus(properties.maximumAge())
        )).isEqualTo("workflow.reconciliation-exhausted");
        assertThat(InvestigationWorkflowReconciliationPreflightPolicy.safeCode(
            occurredAt,
            handoffAt,
            properties.maximumAttempts() + 1,
            properties,
            occurredAt.plus(properties.maximumVerifiableAge())
        )).isEqualTo("workflow.reconciliation-retention-unverifiable");
    }

    private static InvestigationWorkflowReconcilerProperties properties() {
        return new InvestigationWorkflowReconcilerProperties(
            true,
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            8,
            Duration.ofHours(1),
            Duration.ofHours(1),
            Duration.ofSeconds(1),
            Duration.ofMinutes(1),
            Duration.ofSeconds(10),
            Duration.ofDays(30),
            Duration.ofDays(1)
        );
    }
}
