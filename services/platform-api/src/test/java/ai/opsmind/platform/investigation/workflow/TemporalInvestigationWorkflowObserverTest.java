package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.grpc.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.HistoryEventFilterType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TemporalInvestigationWorkflowObserverTest {

    private static final String DIGEST = "a".repeat(64);
    private static final String FIRST_RUN_ID = "first-run-id";
    private static final DataConverter CONVERTER =
        DefaultDataConverter.STANDARD_INSTANCE;

    @Test
    void portAndImplementationExposeObservationOnly() {
        Set<String> portMethods = Arrays.stream(
            InvestigationWorkflowObserver.class.getMethods()
        ).map(Method::getName).collect(Collectors.toSet());
        Set<String> implementationMethods = Arrays.stream(
            TemporalInvestigationWorkflowObserver.class.getMethods()
        ).map(Method::getName).collect(Collectors.toSet());

        assertThat(portMethods).containsExactly("observeExactWorkflow");
        assertThat(implementationMethods)
            .contains("observeExactWorkflow")
            .noneMatch(name -> name.matches(
                "(?i).*(start|signal|update|query|cancel|terminate).*"
            ));
    }

    @Test
    void describeUsesWorkflowIdOnlyThenReadsOneFirstRunHistoryEvent() {
        InvestigationWorkflowStartRequest expected = request();
        WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
        WorkflowServiceBlockingStub blocking = mock(WorkflowServiceBlockingStub.class);
        when(stubs.blockingStub()).thenReturn(blocking);
        when(blocking.describeWorkflowExecution(any()))
            .thenReturn(description(expected));
        when(blocking.getWorkflowExecutionHistory(any()))
            .thenReturn(history(expected));

        InvestigationWorkflowObservation observed = observer(stubs)
            .observeExactWorkflow(expected, DIGEST);

        assertThat(observed.outcome())
            .isEqualTo(InvestigationWorkflowObservation.Outcome.MATCH);
        assertThat(observed.firstRunId()).isEqualTo(FIRST_RUN_ID);

        ArgumentCaptor<DescribeWorkflowExecutionRequest> describe =
            ArgumentCaptor.forClass(DescribeWorkflowExecutionRequest.class);
        verify(blocking).describeWorkflowExecution(describe.capture());
        assertThat(describe.getValue().getExecution().getWorkflowId())
            .isEqualTo(expected.workflowId());
        assertThat(describe.getValue().getExecution().getRunId()).isEmpty();

        ArgumentCaptor<GetWorkflowExecutionHistoryRequest> history =
            ArgumentCaptor.forClass(GetWorkflowExecutionHistoryRequest.class);
        verify(blocking).getWorkflowExecutionHistory(history.capture());
        assertThat(history.getValue().getExecution().getWorkflowId())
            .isEqualTo(expected.workflowId());
        assertThat(history.getValue().getExecution().getRunId())
            .isEqualTo(FIRST_RUN_ID);
        assertThat(history.getValue().getMaximumPageSize()).isEqualTo(1);
        assertThat(history.getValue().getWaitNewEvent()).isFalse();
        assertThat(history.getValue().getHistoryEventFilterType())
            .isEqualTo(HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_ALL_EVENT);
    }

    @Test
    void onlyDescribeNotFoundIsQualifiedAbsence() {
        WorkflowServiceStubs absentStubs = stubsThrowingDescribe(
            Status.NOT_FOUND.asRuntimeException()
        );
        InvestigationWorkflowObservation absent = observer(absentStubs)
            .observeExactWorkflow(request(), DIGEST);

        assertThat(absent.outcome())
            .isEqualTo(InvestigationWorkflowObservation.Outcome.ABSENT);
        assertThat(absent.safeCode())
            .isEqualTo("workflow.temporal-start-not-found");

        WorkflowServiceStubs historyMissing = mock(WorkflowServiceStubs.class);
        WorkflowServiceBlockingStub blocking = mock(WorkflowServiceBlockingStub.class);
        when(historyMissing.blockingStub()).thenReturn(blocking);
        when(blocking.describeWorkflowExecution(any())).thenReturn(description(request()));
        when(blocking.getWorkflowExecutionHistory(any()))
            .thenThrow(Status.NOT_FOUND.asRuntimeException());

        assertThat(observer(historyMissing).observeExactWorkflow(request(), DIGEST))
            .extracting(InvestigationWorkflowObservation::outcome)
            .isEqualTo(InvestigationWorkflowObservation.Outcome.BLOCKED);
    }

    @Test
    void unavailableRetriesWhilePermissionFailureBlocks() {
        InvestigationWorkflowObservation unavailable = observer(stubsThrowingDescribe(
            Status.UNAVAILABLE.asRuntimeException()
        )).observeExactWorkflow(request(), DIGEST);
        assertThat(unavailable.outcome())
            .isEqualTo(InvestigationWorkflowObservation.Outcome.RETRY);
        assertThat(unavailable.safeCode())
            .isEqualTo("workflow.temporal-unavailable");
        assertThat(observer(stubsThrowingDescribe(
            Status.PERMISSION_DENIED.asRuntimeException()
        )).observeExactWorkflow(request(), DIGEST).outcome())
            .isEqualTo(InvestigationWorkflowObservation.Outcome.BLOCKED);
    }

    private TemporalInvestigationWorkflowObserver observer(WorkflowServiceStubs stubs) {
        return new TemporalInvestigationWorkflowObserver(
            stubs,
            observerProperties(),
            workflowProperties(),
            CONVERTER,
            new InvestigationWorkflowReconciliationMetrics(new SimpleMeterRegistry())
        );
    }

    private WorkflowServiceStubs stubsThrowingDescribe(RuntimeException failure) {
        WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
        WorkflowServiceBlockingStub blocking = mock(WorkflowServiceBlockingStub.class);
        when(stubs.blockingStub()).thenReturn(blocking);
        when(blocking.describeWorkflowExecution(any())).thenThrow(failure);
        return stubs;
    }

    private DescribeWorkflowExecutionResponse description(
        InvestigationWorkflowStartRequest expected
    ) {
        return DescribeWorkflowExecutionResponse.newBuilder()
            .setWorkflowExecutionInfo(WorkflowExecutionInfo.newBuilder()
                .setExecution(WorkflowExecution.newBuilder()
                    .setWorkflowId(expected.workflowId())
                    .setRunId("current-run-id"))
                .setType(WorkflowType.newBuilder().setName(expected.workflowType()))
                .setTaskQueue(expected.taskQueue())
                .setFirstRunId(FIRST_RUN_ID)
                .setMemo(memo()))
            .build();
    }

    private GetWorkflowExecutionHistoryResponse history(
        InvestigationWorkflowStartRequest expected
    ) {
        return GetWorkflowExecutionHistoryResponse.newBuilder()
            .setHistory(History.newBuilder().addEvents(HistoryEvent.newBuilder()
                .setEventId(1)
                .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
                .setWorkflowExecutionStartedEventAttributes(
                    WorkflowExecutionStartedEventAttributes.newBuilder()
                        .setWorkflowId(expected.workflowId())
                        .setWorkflowType(WorkflowType.newBuilder()
                            .setName(expected.workflowType()))
                        .setTaskQueue(TaskQueue.newBuilder().setName(expected.taskQueue()))
                        .setFirstExecutionRunId(FIRST_RUN_ID)
                        .setOriginalExecutionRunId(FIRST_RUN_ID)
                        .setMemo(memo())
                        .setInput(CONVERTER.toPayloads(expected).orElseThrow())
                )))
            .build();
    }

    private Memo memo() {
        return Memo.newBuilder()
            .putFields(
                "opsmind_start_payload_digest",
                CONVERTER.toPayload(DIGEST).orElseThrow()
            )
            .build();
    }

    private InvestigationTemporalObserverProperties observerProperties() {
        InvestigationTemporalObserverProperties properties =
            new InvestigationTemporalObserverProperties();
        properties.setClusterId("temporal-test");
        properties.setTarget("127.0.0.1:7233");
        properties.setTlsEnabled(false);
        properties.setAllowLocalCleartext(true);
        properties.setRpcTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private InvestigationWorkflowProperties workflowProperties() {
        return new InvestigationWorkflowProperties(
            "temporal-test", "namespace-test", "opsmind-investigation-v1", "queue-test"
        );
    }

    private InvestigationWorkflowStartRequest request() {
        UUID organization = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID run = UUID.fromString("55555555-5555-4555-8555-555555555555");
        return new InvestigationWorkflowStartRequest(
            organization,
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            run,
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            4, 2, 10, 1_000,
            Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-01-01T00:10:00Z"),
            "temporal-test", "namespace-test",
            InvestigationWorkflowStartRequest.workflowId(organization, run),
            "opsmind-investigation-v1", "queue-test", 7, "b".repeat(64)
        );
    }
}
