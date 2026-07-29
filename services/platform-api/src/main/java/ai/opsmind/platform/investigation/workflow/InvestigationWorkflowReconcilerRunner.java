package ai.opsmind.platform.investigation.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "opsmind.investigation.workflow-reconciler",
    name = "enabled",
    havingValue = "true"
)
public final class InvestigationWorkflowReconcilerRunner {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(InvestigationWorkflowReconcilerRunner.class);

    private final InvestigationWorkflowReconciler reconciler;

    public InvestigationWorkflowReconcilerRunner(
        InvestigationWorkflowReconciler reconciler
    ) {
        this.reconciler = reconciler;
    }

    @Scheduled(
        fixedDelayString = "${opsmind.investigation.workflow-reconciler.poll-interval:PT1S}",
        initialDelayString = "${opsmind.investigation.workflow-reconciler.poll-interval:PT1S}"
    )
    public void runOnce() {
        boolean reconciliationSucceeded = true;
        try {
            reconciler.reconcileOne();
        }
        catch (RuntimeException failure) {
            reconciliationSucceeded = false;
            LOGGER.error(
                "Workflow reconciliation cycle failed with safe class {}.",
                failure.getClass().getSimpleName()
            );
        }
        try {
            reconciler.refreshStatus();
        }
        catch (RuntimeException failure) {
            reconciler.markDatabaseUnavailable();
            LOGGER.error(
                "Workflow reconciliation status refresh failed with safe class {}.",
                failure.getClass().getSimpleName()
            );
        }
        if (!reconciliationSucceeded) {
            reconciler.markDatabaseUnavailable();
        }
    }
}
