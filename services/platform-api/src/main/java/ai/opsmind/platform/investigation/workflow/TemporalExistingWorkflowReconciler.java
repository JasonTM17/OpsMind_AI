package ai.opsmind.platform.investigation.workflow;

import java.util.Optional;

import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.TemporalException;

final class TemporalExistingWorkflowReconciler {

    private final WorkflowClient workflowClient;

    TemporalExistingWorkflowReconciler(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    InvestigationWorkflowClient.StartResult reconcile(
        InvestigationWorkflowStartRequest request,
        String startPayloadDigest,
        WorkflowExecutionAlreadyStarted exception
    ) {
        WorkflowExecution execution = exception.getExecution();
        Optional<String> workflowType = exception.getWorkflowType();
        if (execution == null
            || execution.getWorkflowId().isBlank()
            || execution.getRunId().isBlank()
            || workflowType.filter(value -> !value.isBlank()).isEmpty()) {
            throw InvestigationWorkflowStartException.outcomeUncertain(
                "workflow.temporal-outcome-ambiguous", exception
            );
        }
        if (!request.workflowId().equals(execution.getWorkflowId())
            || !request.workflowType().equals(workflowType.orElseThrow())) {
            throw InvestigationWorkflowStartException.permanent(
                "workflow.existing-contract-mismatch", exception
            );
        }
        try {
            WorkflowStub existing = workflowClient.newUntypedWorkflowStub(
                execution,
                Optional.of(request.workflowType())
            );
            WorkflowExecutionDescription description = existing.describe();
            Object storedDigest =
                description.getMemo(TemporalInvestigationWorkflowClient.PAYLOAD_DIGEST_MEMO_KEY, String.class);
            String firstRunId = description.getFirstRunId();
            if (firstRunId == null || firstRunId.isBlank()) {
                throw InvestigationWorkflowStartException.outcomeUncertain(
                    "workflow.temporal-outcome-ambiguous", exception
                );
            }
            if (!matchesExistingExecution(
                request, startPayloadDigest, execution, description, storedDigest, firstRunId
            )) {
                throw InvestigationWorkflowStartException.permanent(
                    "workflow.existing-contract-mismatch", exception
                );
            }
            return new InvestigationWorkflowClient.StartResult(firstRunId, true);
        }
        catch (InvestigationWorkflowStartException mapped) {
            throw mapped;
        }
        catch (StatusRuntimeException status) {
            throw InvestigationWorkflowStartException.outcomeUncertain(
                "workflow.temporal-outcome-ambiguous", status
            );
        }
        catch (TemporalException temporalFailure) {
            throw InvestigationWorkflowStartException.outcomeUncertain(
                "workflow.temporal-outcome-ambiguous", temporalFailure
            );
        }
        catch (RuntimeException unverifiable) {
            throw InvestigationWorkflowStartException.outcomeUncertain(
                "workflow.temporal-outcome-ambiguous", unverifiable
            );
        }
    }

    private boolean matchesExistingExecution(
        InvestigationWorkflowStartRequest request,
        String startPayloadDigest,
        WorkflowExecution execution,
        WorkflowExecutionDescription description,
        Object storedDigest,
        String firstRunId
    ) {
        WorkflowExecution describedExecution = description.getExecution();
        return request.workflowType().equals(description.getWorkflowType())
            && request.taskQueue().equals(description.getTaskQueue())
            && startPayloadDigest.equals(storedDigest)
            && describedExecution != null
            && request.workflowId().equals(describedExecution.getWorkflowId())
            && !execution.getRunId().isBlank()
            && !describedExecution.getRunId().isBlank()
            && execution.equals(describedExecution)
            && firstRunId.equals(description.getFirstRunId())
            && request.equals(readFirstStartInput(WorkflowExecution.newBuilder()
                .setWorkflowId(request.workflowId())
                .setRunId(firstRunId)
                .build()));
    }

    private InvestigationWorkflowStartRequest readFirstStartInput(
        WorkflowExecution execution
    ) {
        HistoryEvent firstEvent = workflowClient.streamHistory(
            execution.getWorkflowId(), execution.getRunId()
        ).findFirst().orElseThrow(() ->
            new IllegalStateException("Existing workflow has no start history.")
        );
        if (firstEvent.getEventType() != EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
            || !firstEvent.hasWorkflowExecutionStartedEventAttributes()
            || !firstEvent.getWorkflowExecutionStartedEventAttributes().hasInput()) {
            throw new IllegalStateException("Existing workflow start history is invalid.");
        }
        return workflowClient.getOptions().getDataConverter().fromPayloads(
            0,
            Optional.of(firstEvent.getWorkflowExecutionStartedEventAttributes().getInput()),
            InvestigationWorkflowStartRequest.class,
            InvestigationWorkflowStartRequest.class
        );
    }
}
