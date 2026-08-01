package ai.opsmind.platform.investigation.workflow;

import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;

public final class ParkedInvestigationWorkflow implements InvestigationWorkflow {

    @Override
    public void run(InvestigationWorkflowStartRequest request) {
        WorkflowInfo info = Workflow.getInfo();
        if (request == null
            || !InvestigationWorkflow.TYPE.equals(info.getWorkflowType())
            || !InvestigationWorkflow.TYPE.equals(request.workflowType())
            || !info.getNamespace().equals(request.temporalNamespace())
            || !info.getWorkflowId().equals(request.workflowId())
            || !info.getTaskQueue().equals(request.taskQueue())) {
            throw new IllegalArgumentException("Workflow start metadata is invalid.");
        }

        // Wait for the workflow-level cancellation request. The cancellation
        // promise is deterministic and does not schedule activity, tool, or IO work.
        CancellationScope.current().getCancellationRequest().get();
        CancellationScope.throwCanceled();
    }
}
