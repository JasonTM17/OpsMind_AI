package ai.opsmind.platform.investigation.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface InvestigationWorkflow {

    String TYPE = "opsmind-investigation-v1";

    @WorkflowMethod(name = TYPE)
    void run(InvestigationWorkflowStartRequest request);
}
