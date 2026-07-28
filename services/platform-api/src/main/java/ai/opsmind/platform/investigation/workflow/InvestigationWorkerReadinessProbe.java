package ai.opsmind.platform.investigation.workflow;

public interface InvestigationWorkerReadinessProbe {

    boolean hasCompatibleWorkflowPoller(
        InvestigationTemporalClientProperties clientProperties,
        InvestigationWorkflowProperties workflowProperties
    );
}
