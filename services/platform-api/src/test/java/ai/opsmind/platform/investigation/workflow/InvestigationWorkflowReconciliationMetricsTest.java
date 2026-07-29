package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

class InvestigationWorkflowReconciliationMetricsTest {

    @Test
    void convergenceTimersUseOnlyTheBoundedTerminalResultLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InvestigationWorkflowReconciliationMetrics metrics =
            new InvestigationWorkflowReconciliationMetrics(registry);

        metrics.recordOutcome("match", Duration.ofSeconds(2));
        metrics.recordOutcome("mismatch", Duration.ofSeconds(3));
        metrics.recordOutcome("exhausted", Duration.ofSeconds(4));
        metrics.recordOutcome("retry", Duration.ofSeconds(1));

        assertThat(registry.find(
            "opsmind.workflow.reconciliation.convergence.duration"
        ).timers()).extracting(timer -> timer.getId().getTag("result"))
            .containsExactlyInAnyOrder("started", "rejected", "blocked");
        assertThat(registry.find(
            "opsmind.workflow.reconciliation.outcomes"
        ).counters()).extracting(counter -> counter.getId().getTag("outcome"))
            .containsExactlyInAnyOrder(
                "match",
                "absence_candidate",
                "verified_absence",
                "released",
                "mismatch",
                "retry",
                "blocked",
                "lease_lost",
                "exhausted"
            );
        assertThat(registry.get(
            "opsmind.workflow.reconciliation.outcomes"
        ).tag("outcome", "absence_candidate").counter().count()).isZero();
        assertThatThrownBy(
            () -> metrics.recordOutcome("tenant-provided-value", Duration.ZERO)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusGaugesAggregateExhaustionWithoutHighCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InvestigationWorkflowReconciliationMetrics metrics =
            new InvestigationWorkflowReconciliationMetrics(registry);

        metrics.updateStatus(new InvestigationWorkflowReconciliationStatus(
            2, 3, 4, 5, 6, 7
        ));

        assertThat(registry.get(
            "opsmind.workflow.reconciliation.blocked"
        ).gauge().value()).isEqualTo(9);
        assertThat(registry.get(
            "opsmind.workflow.reconciliation.retention.ineligible"
        ).gauge().value()).isEqualTo(6);
        assertThat(registry.get(
            "opsmind.workflow.reconciliation.oldest.pending.age.seconds"
        ).gauge().value()).isEqualTo(7);
        assertThat(registry.getMeters())
            .allMatch(meter -> meter.getId().getTags().isEmpty());
    }

    @Test
    void readinessRequiresBothObserverAndDatabaseLane() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InvestigationWorkflowReconciliationMetrics metrics =
            new InvestigationWorkflowReconciliationMetrics(registry);

        metrics.updateObserverReady(true);
        assertThat(ready(registry)).isZero();
        metrics.updateDatabaseReady(true);
        assertThat(ready(registry)).isEqualTo(1);
        metrics.updateObserverReady(false);
        assertThat(ready(registry)).isZero();
    }

    private double ready(SimpleMeterRegistry registry) {
        return registry.get("opsmind.workflow.reconciliation.ready").gauge().value();
    }
}
