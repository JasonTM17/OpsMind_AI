package ai.opsmind.platform.investigation.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "opsmind.investigation.workflow-starter",
    name = "enabled",
    havingValue = "true"
)
public final class InvestigationWorkflowStarterRunner {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(InvestigationWorkflowStarterRunner.class);

    private final InvestigationWorkflowStartTenantScheduler tenantScheduler;
    private final InvestigationWorkflowStartDispatcher dispatcher;
    private final InvestigationWorkflowStarterProperties starterProperties;

    public InvestigationWorkflowStarterRunner(
        InvestigationWorkflowStartTenantScheduler tenantScheduler,
        InvestigationWorkflowStartDispatcher dispatcher,
        InvestigationWorkflowStarterProperties starterProperties
    ) {
        this.tenantScheduler = tenantScheduler;
        this.dispatcher = dispatcher;
        this.starterProperties = starterProperties;
    }

    @Scheduled(
        fixedDelayString = "${opsmind.investigation.workflow-starter.poll-interval:PT1S}",
        initialDelayString = "${opsmind.investigation.workflow-starter.poll-interval:PT1S}"
    )
    public void runOnce() {
        starterProperties.validate();
        try {
            dispatchReadyTenants();
        }
        catch (RuntimeException exception) {
            LOGGER.error(
                "Workflow starter scheduling failed with safe class {}.",
                exception.getClass().getSimpleName()
            );
        }
    }

    private void dispatchReadyTenants() {
        for (var organizationId : tenantScheduler.listReadyTenants(
            starterProperties.tenantLimit()
        )) {
            try {
                dispatcher.dispatchTenant(organizationId);
            }
            catch (RuntimeException exception) {
                LOGGER.error(
                    "Workflow starter tenant batch failed with safe class {}.",
                    exception.getClass().getSimpleName()
                );
            }
        }
    }
}
