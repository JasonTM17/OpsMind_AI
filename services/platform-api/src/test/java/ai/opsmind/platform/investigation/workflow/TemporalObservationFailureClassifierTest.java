package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;

import org.junit.jupiter.api.Test;

class TemporalObservationFailureClassifierTest {

    @Test
    void authenticationFailuresUseThePermissionBlockedCode() {
        assertThat(TemporalObservationFailureClassifier.describe(
            Status.UNAUTHENTICATED.asRuntimeException()
        ).safeCode()).isEqualTo("workflow.reconciliation-permission-denied");
        assertThat(TemporalObservationFailureClassifier.describe(
            Status.PERMISSION_DENIED.asRuntimeException()
        ).safeCode()).isEqualTo("workflow.reconciliation-permission-denied");
    }

    @Test
    void onlyDescribeNotFoundQualifiesAsAbsence() {
        assertThat(TemporalObservationFailureClassifier.describe(
            Status.NOT_FOUND.asRuntimeException()
        ).outcome()).isEqualTo(InvestigationWorkflowObservation.Outcome.ABSENT);
        assertThat(TemporalObservationFailureClassifier.history(
            Status.NOT_FOUND.asRuntimeException()
        ).safeCode()).isEqualTo("workflow.reconciliation-history-disappeared");
    }
}
