package ai.opsmind.platform.investigation.workflow;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.HistoryEventFilterType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;

public final class TemporalInvestigationWorkflowObserver
    implements InvestigationWorkflowObserver {

    private static final String SHA_256_HEX = "[0-9a-f]{64}";

    private final WorkflowServiceStubs serviceStubs;
    private final InvestigationTemporalObserverProperties observerProperties;
    private final InvestigationWorkflowProperties workflowProperties;
    private final TemporalWorkflowStartContractVerifier verifier;
    private final InvestigationWorkflowReconciliationMetrics metrics;

    public TemporalInvestigationWorkflowObserver(
        WorkflowServiceStubs serviceStubs,
        InvestigationTemporalObserverProperties observerProperties,
        InvestigationWorkflowProperties workflowProperties,
        DataConverter dataConverter,
        InvestigationWorkflowReconciliationMetrics metrics
    ) {
        this.serviceStubs = serviceStubs;
        this.observerProperties = observerProperties;
        this.workflowProperties = workflowProperties;
        this.verifier = new TemporalWorkflowStartContractVerifier(dataConverter);
        this.metrics = metrics;
    }

    @Override
    public InvestigationWorkflowObservation observeExactWorkflow(
        InvestigationWorkflowStartRequest expected,
        String startPayloadDigest
    ) {
        if (!validTarget(expected, startPayloadDigest)) {
            return ready(InvestigationWorkflowObservation.blocked(
                "workflow.reconciliation-configuration-mismatch"
            ));
        }
        DescribeWorkflowExecutionResponse description;
        try {
            description = metrics.observe("describe", () ->
                serviceStubs.blockingStub().describeWorkflowExecution(
                    DescribeWorkflowExecutionRequest.newBuilder()
                        .setNamespace(expected.temporalNamespace())
                        .setExecution(WorkflowExecution.newBuilder()
                            .setWorkflowId(expected.workflowId())
                            .build())
                        .build()
                )
            );
        }
        catch (RuntimeException failure) {
            return ready(TemporalObservationFailureClassifier.describe(failure));
        }
        var descriptionResult = verifier.verifyDescription(
            expected, description
        );
        if (!descriptionResult.matched()) {
            return ready(descriptionResult.observation());
        }

        GetWorkflowExecutionHistoryResponse history;
        try {
            history = metrics.observe("first_history", () ->
                serviceStubs.blockingStub().getWorkflowExecutionHistory(
                    GetWorkflowExecutionHistoryRequest.newBuilder()
                        .setNamespace(expected.temporalNamespace())
                        .setExecution(WorkflowExecution.newBuilder()
                            .setWorkflowId(expected.workflowId())
                            .setRunId(descriptionResult.firstRunId())
                            .build())
                        .setMaximumPageSize(1)
                        .setWaitNewEvent(false)
                        .setHistoryEventFilterType(
                            HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_ALL_EVENT
                        )
                        .build()
                )
            );
        }
        catch (RuntimeException failure) {
            return ready(TemporalObservationFailureClassifier.history(failure));
        }
        if (history == null || !history.hasHistory()
            || history.getHistory().getEventsCount() != 1) {
            return ready(InvestigationWorkflowObservation.blocked(
                "workflow.reconciliation-history-malformed"
            ));
        }
        HistoryEvent firstEvent = history.getHistory().getEvents(0);
        if (firstEvent.getEventType()
            != EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED) {
            return ready(InvestigationWorkflowObservation.blocked(
                "workflow.reconciliation-history-malformed"
            ));
        }
        return ready(verifier.verifyHistory(
            expected, startPayloadDigest, descriptionResult.firstRunId(), firstEvent
        ));
    }

    private boolean validTarget(
        InvestigationWorkflowStartRequest expected,
        String startPayloadDigest
    ) {
        try {
            observerProperties.validate(workflowProperties);
            return expected != null
                && observerProperties.getClusterId().equals(expected.temporalClusterId())
                && workflowProperties.namespace().equals(expected.temporalNamespace())
                && workflowProperties.workflowType().equals(expected.workflowType())
                && workflowProperties.taskQueue().equals(expected.taskQueue())
                && startPayloadDigest != null
                && startPayloadDigest.matches(SHA_256_HEX);
        }
        catch (IllegalStateException failure) {
            return false;
        }
    }

    private InvestigationWorkflowObservation ready(
        InvestigationWorkflowObservation observation
    ) {
        metrics.updateObserverReady(
            observation.outcome() != InvestigationWorkflowObservation.Outcome.RETRY
                && observation.outcome() != InvestigationWorkflowObservation.Outcome.BLOCKED
        );
        return observation;
    }
}
