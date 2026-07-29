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
        InvestigationWorkflowReconcilerProperties properties =
            new InvestigationWorkflowReconcilerProperties(
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
        Instant now = Instant.parse("2030-01-01T02:00:00Z");

        assertThat(InvestigationWorkflowReconciler.preflightSafeCode(
            now.minus(Duration.ofHours(2)),
            now.minus(Duration.ofMinutes(1)),
            1,
            properties,
            now
        )).isEqualTo("workflow.reconciliation-handoff-age-exceeded");
    }

    @Test
    void aTimelyHandoffCanReconcileAfterTheHandoffBoundary() {
        InvestigationWorkflowReconcilerProperties properties =
            new InvestigationWorkflowReconcilerProperties(
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
        Instant occurredAt = Instant.parse("2030-01-01T00:00:00Z");

        assertThat(InvestigationWorkflowReconciler.preflightSafeCode(
            occurredAt,
            occurredAt.plus(Duration.ofMinutes(59)),
            2,
            properties,
            occurredAt.plus(Duration.ofMinutes(61))
        )).isNull();
    }
}
