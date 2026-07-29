package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InvestigationWorkflowObservationTest {

    @Test
    void settlementValuesMustFitTheDatabaseContract() {
        assertThatThrownBy(() -> InvestigationWorkflowObservation.match("x".repeat(256)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvestigationWorkflowObservation.retry(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvestigationWorkflowObservation.blocked("unsafe code"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvestigationWorkflowObservation(
            InvestigationWorkflowObservation.Outcome.RETRY,
            "unexpected-run-id",
            "workflow.temporal-unavailable"
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
