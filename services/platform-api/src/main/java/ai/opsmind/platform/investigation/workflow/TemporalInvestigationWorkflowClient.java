package ai.opsmind.platform.investigation.workflow;

import java.util.Map;

import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.TemporalException;

public final class TemporalInvestigationWorkflowClient implements InvestigationWorkflowClient {

    static final String PAYLOAD_DIGEST_MEMO_KEY = "opsmind_start_payload_digest";
    private static final String SHA_256_HEX = "[0-9a-f]{64}";

    private final WorkflowClient workflowClient;
    private final InvestigationTemporalClientProperties clientProperties;
    private final InvestigationWorkflowProperties workflowProperties;
    private final TemporalExistingWorkflowReconciler existingWorkflowReconciler;

    public TemporalInvestigationWorkflowClient(
        WorkflowClient workflowClient,
        InvestigationTemporalClientProperties clientProperties,
        InvestigationWorkflowProperties workflowProperties
    ) {
        this.workflowClient = workflowClient;
        this.clientProperties = clientProperties;
        this.workflowProperties = workflowProperties;
        this.existingWorkflowReconciler = new TemporalExistingWorkflowReconciler(workflowClient);
    }

    @Override
    public StartResult start(
        InvestigationWorkflowStartRequest request,
        String startPayloadDigest
    ) {
        requireExpectedTarget(request, startPayloadDigest);
        WorkflowOptions options = WorkflowOptions.newBuilder()
            .setWorkflowId(request.workflowId())
            .setTaskQueue(request.taskQueue())
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE
            )
            .setWorkflowIdConflictPolicy(
                WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL
            )
            .setMemo(Map.of(PAYLOAD_DIGEST_MEMO_KEY, startPayloadDigest))
            .build();
        WorkflowStub stub = workflowClient.newUntypedWorkflowStub(request.workflowType(), options);
        try {
            WorkflowExecution execution = stub.start(request);
            return result(execution, false);
        }
        catch (WorkflowExecutionAlreadyStarted exception) {
            return existingWorkflowReconciler.reconcile(request, startPayloadDigest, exception);
        }
        catch (StatusRuntimeException exception) {
            throw TemporalTransportFailureClassifier.map(
                exception, "workflow.temporal-rejected"
            );
        }
        catch (TemporalException exception) {
            throw TemporalTransportFailureClassifier.map(
                exception, "workflow.temporal-rejected"
            );
        }
    }

    private void requireExpectedTarget(
        InvestigationWorkflowStartRequest request,
        String startPayloadDigest
    ) {
        clientProperties.validate(workflowProperties);
        if (!clientProperties.clusterId().equals(request.temporalClusterId())
            || !workflowProperties.namespace().equals(request.temporalNamespace())
            || !workflowProperties.workflowType().equals(request.workflowType())
            || !workflowProperties.taskQueue().equals(request.taskQueue())
            || startPayloadDigest == null || !startPayloadDigest.matches(SHA_256_HEX)) {
            throw InvestigationWorkflowStartException.permanent(
                "workflow.target-mismatch", null
            );
        }
    }

    private StartResult result(WorkflowExecution execution, boolean alreadyStarted) {
        if (execution == null || execution.getRunId().isBlank()) {
            throw InvestigationWorkflowStartException.outcomeUncertain(
                "workflow.temporal-outcome-ambiguous", null
            );
        }
        return new StartResult(execution.getRunId(), alreadyStarted);
    }

}
