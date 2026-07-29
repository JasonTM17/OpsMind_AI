package ai.opsmind.platform.investigation.workflow;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class InvestigationWorkflowReconcilerRunnerTest {

    @Test
    void databaseTimeoutClearsReadinessForClaimAndStatusFailures() {
        InvestigationWorkflowReconciler reconciler =
            mock(InvestigationWorkflowReconciler.class);
        doThrow(new QueryTimeoutException("synthetic timeout"))
            .when(reconciler).reconcileOne();
        doThrow(new QueryTimeoutException("synthetic timeout"))
            .when(reconciler).refreshStatus();

        new InvestigationWorkflowReconcilerRunner(reconciler).runOnce();

        verify(reconciler, times(2)).markDatabaseUnavailable();
    }
}
