package ai.opsmind.platform.investigation.workflow;

public interface InvestigationWorkflowObserver {

    InvestigationWorkflowObservation observeExactWorkflow(
        InvestigationWorkflowStartRequest expected,
        String startPayloadDigest
    );
}
