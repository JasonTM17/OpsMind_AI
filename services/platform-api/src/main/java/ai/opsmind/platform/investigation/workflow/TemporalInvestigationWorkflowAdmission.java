package ai.opsmind.platform.investigation.workflow;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.http.HttpStatus;

public final class TemporalInvestigationWorkflowAdmission
    implements InvestigationWorkflowAdmission {

    private final InvestigationTemporalClientProperties clientProperties;
    private final InvestigationWorkerReadinessProbe readinessProbe;

    public TemporalInvestigationWorkflowAdmission(
        InvestigationTemporalClientProperties clientProperties,
        InvestigationWorkerReadinessProbe readinessProbe
    ) {
        this.clientProperties = clientProperties;
        this.readinessProbe = readinessProbe;
    }

    @Override
    public void requireReady(InvestigationWorkflowProperties workflowProperties) {
        try {
            clientProperties.validate(workflowProperties);
            if (!readinessProbe.hasCompatibleWorkflowPoller(
                clientProperties, workflowProperties
            )) {
                throw unavailable(null);
            }
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private PlatformProblemException unavailable(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "investigation.workflow-not-ready",
            "Durable investigation execution has no compatible ready worker.",
            cause
        );
    }
}
