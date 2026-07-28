package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import io.grpc.Status;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TemporalInvestigationWorkflowClientTest {

    private static final String WORKFLOW_TYPE = "opsmind-investigation-v1";
    private static final String TASK_QUEUE = "opsmind-investigation-test";
    private static final String DIGEST = "a".repeat(64);
    private static final DataConverter DATA_CONVERTER =
        DataConverter.getDefaultInstance();

    @Test
    void exactDuplicateReconcilesButConflictingInputDigestIsRejected() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(TestInvestigationWorkflowImpl.class);
            environment.start();
            WorkflowClient sdkClient = environment.getWorkflowClient();
            InvestigationWorkflowProperties workflowProperties =
                new InvestigationWorkflowProperties(
                    "temporal-test", sdkClient.getOptions().getNamespace(),
                    WORKFLOW_TYPE, TASK_QUEUE
                );
            TemporalInvestigationWorkflowClient client =
                new TemporalInvestigationWorkflowClient(
                    sdkClient, clientProperties(), workflowProperties
                );
            InvestigationWorkflowStartRequest request =
                request(sdkClient.getOptions().getNamespace());

            InvestigationWorkflowClient.StartResult first = client.start(request, DIGEST);
            sdkClient.newUntypedWorkflowStub(
                WorkflowExecution.newBuilder()
                    .setWorkflowId(request.workflowId())
                    .setRunId(first.temporalRunId())
                    .build(),
                Optional.of(WORKFLOW_TYPE)
            ).getResult(Void.class);
            InvestigationWorkflowClient.StartResult duplicate = client.start(request, DIGEST);

            assertThat(first.alreadyStarted()).isFalse();
            assertThat(duplicate.alreadyStarted()).isTrue();
            assertThat(duplicate.temporalRunId()).isEqualTo(first.temporalRunId());
            assertThatThrownBy(() -> client.start(request, "b".repeat(64)))
                .isInstanceOfSatisfying(
                    InvestigationWorkflowStartException.class,
                    exception -> {
                        assertThat(exception.retryable()).isFalse();
                        assertThat(exception.code())
                            .isEqualTo("workflow.existing-contract-mismatch");
                        assertThat(exception.getMessage())
                            .isEqualTo("workflow.existing-contract-mismatch");
                    }
                );
        }
    }

    @Test
    void matchingMemoCannotHideDifferentFirstStartInput() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(TestInvestigationWorkflowImpl.class);
            environment.start();
            WorkflowClient sdkClient = environment.getWorkflowClient();
            InvestigationWorkflowProperties workflowProperties =
                new InvestigationWorkflowProperties(
                    "temporal-test", sdkClient.getOptions().getNamespace(),
                    WORKFLOW_TYPE, TASK_QUEUE
                );
            TemporalInvestigationWorkflowClient client =
                new TemporalInvestigationWorkflowClient(
                    sdkClient, clientProperties(), workflowProperties
                );
            InvestigationWorkflowStartRequest expected =
                request(sdkClient.getOptions().getNamespace());
            InvestigationWorkflowStartRequest differentInput =
                withMaxRounds(expected, expected.maxRounds() + 1);
            WorkflowStub existing = sdkClient.newUntypedWorkflowStub(
                WORKFLOW_TYPE,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(expected.workflowId())
                    .setTaskQueue(TASK_QUEUE)
                    .setMemo(Map.of(
                        TemporalInvestigationWorkflowClient.PAYLOAD_DIGEST_MEMO_KEY,
                        DIGEST
                    ))
                    .build()
            );
            existing.start(differentInput);
            existing.getResult(Void.class);

            assertThatThrownBy(() -> client.start(expected, DIGEST))
                .isInstanceOfSatisfying(
                    InvestigationWorkflowStartException.class,
                    exception -> {
                        assertThat(exception.retryable()).isFalse();
                        assertThat(exception.code())
                            .isEqualTo("workflow.existing-contract-mismatch");
                    }
                );
        }
    }

    @ParameterizedTest
    @MethodSource("ambiguousTransportFailureCases")
    void ambiguousTransportFailureOnStartIsRetryable(
        TransportFailureCase failureCase
    ) {
        String namespace = "temporal-test-namespace";
        InvestigationWorkflowStartRequest request = request(namespace);
        WorkflowExecution execution = execution(request);
        WorkflowClient sdkClient = mock(WorkflowClient.class);
        WorkflowStub startStub = mock(WorkflowStub.class);
        when(sdkClient.newUntypedWorkflowStub(
            eq(WORKFLOW_TYPE), any(WorkflowOptions.class)
        )).thenReturn(startStub);
        when(startStub.start(any(Object[].class)))
            .thenThrow(failureCase.failure(execution));

        assertMappedFailure(
            temporalClient(sdkClient, namespace),
            request,
            true,
            "workflow.temporal-unavailable"
        );
    }

    @Test
    void retryAfterAmbiguousStartReconcilesDeterministicExistingExecution() {
        String namespace = "temporal-test-namespace";
        InvestigationWorkflowStartRequest request = request(namespace);
        WorkflowExecution execution = execution(request);
        WorkflowClient sdkClient = mock(WorkflowClient.class);
        WorkflowStub startStub = mock(WorkflowStub.class);
        WorkflowStub existingStub = mock(WorkflowStub.class);
        WorkflowExecutionAlreadyStarted alreadyStarted =
            new WorkflowExecutionAlreadyStarted(
                execution,
                WORKFLOW_TYPE,
                Status.ALREADY_EXISTS.asRuntimeException()
            );
        when(sdkClient.newUntypedWorkflowStub(
            eq(WORKFLOW_TYPE), any(WorkflowOptions.class)
        )).thenReturn(startStub);
        when(startStub.start(any(Object[].class)))
            .thenThrow(statuslessTransportServiceFailure(execution))
            .thenThrow(alreadyStarted);
        when(sdkClient.newUntypedWorkflowStub(
            eq(execution), eq(Optional.of(WORKFLOW_TYPE))
        )).thenReturn(existingStub);
        when(existingStub.describe()).thenReturn(description(execution));
        when(sdkClient.getOptions()).thenReturn(
            WorkflowClientOptions.newBuilder()
                .setDataConverter(DATA_CONVERTER)
                .build()
        );
        when(sdkClient.streamHistory(
            request.workflowId(), execution.getRunId()
        )).thenReturn(Stream.of(startEvent(request)));
        TemporalInvestigationWorkflowClient client =
            temporalClient(sdkClient, namespace);

        assertMappedFailure(
            client,
            request,
            true,
            "workflow.temporal-unavailable"
        );

        InvestigationWorkflowClient.StartResult duplicate =
            client.start(request, DIGEST);

        assertThat(duplicate.alreadyStarted()).isTrue();
        assertThat(duplicate.temporalRunId()).isEqualTo(execution.getRunId());
    }

    @Test
    void statuslessLocalFailureOnStartRemainsPermanent() {
        String namespace = "temporal-test-namespace";
        InvestigationWorkflowStartRequest request = request(namespace);
        WorkflowExecution execution = execution(request);
        WorkflowClient sdkClient = mock(WorkflowClient.class);
        WorkflowStub startStub = mock(WorkflowStub.class);
        when(sdkClient.newUntypedWorkflowStub(
            eq(WORKFLOW_TYPE), any(WorkflowOptions.class)
        )).thenReturn(startStub);
        when(startStub.start(any(Object[].class)))
            .thenThrow(statuslessLocalServiceFailure(execution));

        assertMappedFailure(
            temporalClient(sdkClient, namespace),
            request,
            false,
            "workflow.temporal-rejected"
        );
    }

    @Test
    void statuslessLocalConverterFailureOnStartRemainsPermanent() {
        String namespace = "temporal-test-namespace";
        InvestigationWorkflowStartRequest request = request(namespace);
        WorkflowExecution execution = execution(request);
        WorkflowClient sdkClient = mock(WorkflowClient.class);
        WorkflowStub startStub = mock(WorkflowStub.class);
        when(sdkClient.newUntypedWorkflowStub(
            eq(WORKFLOW_TYPE), any(WorkflowOptions.class)
        )).thenReturn(startStub);
        when(startStub.start(any(Object[].class)))
            .thenThrow(statuslessLocalConverterFailure(execution));

        assertMappedFailure(
            temporalClient(sdkClient, namespace),
            request,
            false,
            "workflow.temporal-rejected"
        );
    }

    @ParameterizedTest
    @MethodSource("nonRetryableTransportCodes")
    void wrappedNonRetryableTransportFailureOnStartRemainsPermanent(
        Status.Code statusCode
    ) {
        String namespace = "temporal-test-namespace";
        InvestigationWorkflowStartRequest request = request(namespace);
        WorkflowExecution execution = execution(request);
        WorkflowClient sdkClient = mock(WorkflowClient.class);
        WorkflowStub startStub = mock(WorkflowStub.class);
        when(sdkClient.newUntypedWorkflowStub(
            eq(WORKFLOW_TYPE), any(WorkflowOptions.class)
        )).thenReturn(startStub);
        when(startStub.start(any(Object[].class)))
            .thenThrow(wrappedServiceFailure(execution, statusCode));

        assertMappedFailure(
            temporalClient(sdkClient, namespace),
            request,
            false,
            "workflow.temporal-rejected"
        );
    }

    @Test
    void checkedNonRetryableTransportFailureOnStartRemainsPermanent() {
        String namespace = "temporal-test-namespace";
        InvestigationWorkflowStartRequest request = request(namespace);
        WorkflowExecution execution = execution(request);
        WorkflowClient sdkClient = mock(WorkflowClient.class);
        WorkflowStub startStub = mock(WorkflowStub.class);
        when(sdkClient.newUntypedWorkflowStub(
            eq(WORKFLOW_TYPE), any(WorkflowOptions.class)
        )).thenReturn(startStub);
        when(startStub.start(any(Object[].class)))
            .thenThrow(wrappedCheckedServiceFailure(
                execution,
                Status.Code.PERMISSION_DENIED
            ));

        assertMappedFailure(
            temporalClient(sdkClient, namespace),
            request,
            false,
            "workflow.temporal-rejected"
        );
    }

    @ParameterizedTest
    @MethodSource("ambiguousTransportFailureCases")
    void ambiguousTransportFailureWhileDescribingExistingIsRetryable(
        TransportFailureCase failureCase
    ) {
        assertReconciliationFailure(
            true,
            failureCase::failure,
            true,
            "workflow.temporal-unavailable"
        );
    }

    @ParameterizedTest
    @MethodSource("ambiguousTransportFailureCases")
    void ambiguousTransportFailureWhileReadingHistoryIsRetryable(
        TransportFailureCase failureCase
    ) {
        assertReconciliationFailure(
            false,
            failureCase::failure,
            true,
            "workflow.temporal-unavailable"
        );
    }

    @ParameterizedTest
    @MethodSource("permanentReconciliationFailureCases")
    void permanentReconciliationFailureUsesUnverifiableCode(
        boolean failDescribe,
        Status.Code statusCode
    ) {
        assertReconciliationFailure(
            failDescribe,
            execution -> wrappedServiceFailure(execution, statusCode),
            false,
            "workflow.existing-contract-unverifiable"
        );
    }

    @Test
    void statuslessLocalFailureDuringReconciliationRemainsPermanent() {
        assertReconciliationFailure(
            false,
            TemporalInvestigationWorkflowClientTest::statuslessLocalServiceFailure,
            false,
            "workflow.existing-contract-unverifiable"
        );
    }

    private void assertReconciliationFailure(
        boolean failDescribe,
        Function<WorkflowExecution, RuntimeException> failureFactory,
        boolean retryable,
        String expectedCode
    ) {
        String namespace = "temporal-test-namespace";
        InvestigationWorkflowStartRequest request = request(namespace);
        WorkflowExecution execution = execution(request);
        WorkflowClient sdkClient = mock(WorkflowClient.class);
        WorkflowStub startStub = mock(WorkflowStub.class);
        WorkflowStub existingStub = mock(WorkflowStub.class);
        WorkflowExecutionAlreadyStarted alreadyStarted =
            new WorkflowExecutionAlreadyStarted(
                execution,
                WORKFLOW_TYPE,
                Status.ALREADY_EXISTS.asRuntimeException()
            );
        RuntimeException transportFailure = failureFactory.apply(execution);
        when(sdkClient.newUntypedWorkflowStub(
            eq(WORKFLOW_TYPE), any(WorkflowOptions.class)
        )).thenReturn(startStub);
        when(startStub.start(any(Object[].class))).thenThrow(alreadyStarted);
        when(sdkClient.newUntypedWorkflowStub(
            eq(execution), eq(Optional.of(WORKFLOW_TYPE))
        )).thenReturn(existingStub);

        if (failDescribe) {
            when(existingStub.describe()).thenThrow(transportFailure);
        }
        else {
            when(existingStub.describe()).thenReturn(description(execution));
            when(sdkClient.getOptions()).thenReturn(
                WorkflowClientOptions.newBuilder()
                    .setDataConverter(DATA_CONVERTER)
                    .build()
            );
            when(sdkClient.streamHistory(
                request.workflowId(), execution.getRunId()
            )).thenThrow(transportFailure);
        }

        assertMappedFailure(
            temporalClient(sdkClient, namespace),
            request,
            retryable,
            expectedCode
        );
    }

    private TemporalInvestigationWorkflowClient temporalClient(
        WorkflowClient sdkClient,
        String namespace
    ) {
        return new TemporalInvestigationWorkflowClient(
            sdkClient,
            clientProperties(),
            new InvestigationWorkflowProperties(
                "temporal-test", namespace, WORKFLOW_TYPE, TASK_QUEUE
            )
        );
    }

    private void assertMappedFailure(
        TemporalInvestigationWorkflowClient client,
        InvestigationWorkflowStartRequest request,
        boolean retryable,
        String code
    ) {
        assertThatThrownBy(() -> client.start(request, DIGEST))
            .isInstanceOfSatisfying(
                InvestigationWorkflowStartException.class,
                exception -> {
                    assertThat(exception.retryable()).isEqualTo(retryable);
                    assertThat(exception.code()).isEqualTo(code);
                    assertThat(exception.getMessage()).isEqualTo(code);
                }
            );
    }

    private static WorkflowServiceException wrappedServiceFailure(
        WorkflowExecution execution,
        Status.Code statusCode
    ) {
        return new WorkflowServiceException(
            execution,
            WORKFLOW_TYPE,
            new IllegalStateException(
                "SDK transport wrapper",
                Status.fromCode(statusCode)
                    .withDescription("sensitive transport detail")
                    .asRuntimeException()
            )
        );
    }

    private static WorkflowServiceException wrappedCheckedServiceFailure(
        WorkflowExecution execution,
        Status.Code statusCode
    ) {
        return new WorkflowServiceException(
            execution,
            WORKFLOW_TYPE,
            new IllegalStateException(
                "SDK checked transport wrapper",
                Status.fromCode(statusCode)
                    .withDescription("sensitive transport detail")
                    .asException()
            )
        );
    }

    private static WorkflowServiceException statuslessTransportServiceFailure(
        WorkflowExecution execution
    ) {
        return new WorkflowServiceException(
            execution,
            WORKFLOW_TYPE,
            new SocketTimeoutException("Temporal RPC timed out without a gRPC status")
        );
    }

    private static WorkflowServiceException statuslessLocalServiceFailure(
        WorkflowExecution execution
    ) {
        return new WorkflowServiceException(
            execution,
            WORKFLOW_TYPE,
            new IllegalStateException("Local SDK/interceptor failure")
        );
    }

    private static WorkflowServiceException statuslessLocalConverterFailure(
        WorkflowExecution execution
    ) {
        return new WorkflowServiceException(
            execution,
            WORKFLOW_TYPE,
            new DataConverterException(
                new SocketTimeoutException("Local converter stream rejected input")
            )
        );
    }

    private WorkflowExecutionDescription description(WorkflowExecution execution) {
        Payload digestPayload = DATA_CONVERTER
            .toPayload(DIGEST)
            .orElseThrow();
        DescribeWorkflowExecutionResponse response =
            DescribeWorkflowExecutionResponse.newBuilder()
                .setWorkflowExecutionInfo(
                    WorkflowExecutionInfo.newBuilder()
                        .setExecution(execution)
                        .setType(
                            WorkflowType.newBuilder().setName(WORKFLOW_TYPE).build()
                        )
                        .setTaskQueue(TASK_QUEUE)
                        .setMemo(
                            Memo.newBuilder()
                                .putFields(
                                    TemporalInvestigationWorkflowClient
                                        .PAYLOAD_DIGEST_MEMO_KEY,
                                    digestPayload
                                )
                                .build()
                        )
                        .build()
                )
                .build();
        return new WorkflowExecutionDescription(response, DATA_CONVERTER);
    }

    private HistoryEvent startEvent(InvestigationWorkflowStartRequest request) {
        Payloads input = DATA_CONVERTER
            .toPayloads(request)
            .orElseThrow();
        return HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .setWorkflowExecutionStartedEventAttributes(
                WorkflowExecutionStartedEventAttributes.newBuilder()
                    .setInput(input)
                    .build()
            )
            .build();
    }

    private WorkflowExecution execution(InvestigationWorkflowStartRequest request) {
        return WorkflowExecution.newBuilder()
            .setWorkflowId(request.workflowId())
            .setRunId("temporal-run-1")
            .build();
    }

    private static Stream<TransportFailureCase> ambiguousTransportFailureCases() {
        return Stream.of(
            transportFailureCase("UNAVAILABLE", Status.Code.UNAVAILABLE),
            transportFailureCase("DEADLINE_EXCEEDED", Status.Code.DEADLINE_EXCEEDED),
            transportFailureCase("RESOURCE_EXHAUSTED", Status.Code.RESOURCE_EXHAUSTED),
            transportFailureCase("ABORTED", Status.Code.ABORTED),
            transportFailureCase("UNKNOWN", Status.Code.UNKNOWN),
            transportFailureCase("INTERNAL", Status.Code.INTERNAL),
            transportFailureCase("CANCELLED", Status.Code.CANCELLED),
            new TransportFailureCase(
                "checked-UNAVAILABLE",
                execution -> wrappedCheckedServiceFailure(
                    execution,
                    Status.Code.UNAVAILABLE
                )
            ),
            new TransportFailureCase(
                "statusless-temporal-wrapper",
                TemporalInvestigationWorkflowClientTest::statuslessTransportServiceFailure
            )
        );
    }

    private static TransportFailureCase transportFailureCase(
        String name,
        Status.Code statusCode
    ) {
        return new TransportFailureCase(
            name,
            execution -> wrappedServiceFailure(execution, statusCode)
        );
    }

    private static Stream<Status.Code> nonRetryableTransportCodes() {
        return Stream.of(Status.Code.values())
            .filter(code -> switch (code) {
                case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED,
                    UNKNOWN, INTERNAL, CANCELLED ->
                    false;
                default -> true;
            });
    }

    private static Stream<Arguments> permanentReconciliationFailureCases() {
        return Stream.of(
            Arguments.of(true, Status.Code.NOT_FOUND),
            Arguments.of(true, Status.Code.PERMISSION_DENIED),
            Arguments.of(false, Status.Code.NOT_FOUND),
            Arguments.of(false, Status.Code.PERMISSION_DENIED)
        );
    }

    private record TransportFailureCase(
        String name,
        Function<WorkflowExecution, RuntimeException> factory
    ) {
        private RuntimeException failure(WorkflowExecution execution) {
            return factory.apply(execution);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private InvestigationTemporalClientProperties clientProperties() {
        return new InvestigationTemporalClientProperties(
            "temporal-test", "127.0.0.1:7233", false, true,
            Duration.ofSeconds(5), "test-worker", "test-build"
        );
    }

    private InvestigationWorkflowStartRequest request(String namespace) {
        UUID organizationId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID runId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        return new InvestigationWorkflowStartRequest(
            organizationId,
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            runId,
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            4, 2, 10, 1_000,
            Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-01-01T00:10:00Z"),
            "temporal-test", namespace,
            InvestigationWorkflowStartRequest.workflowId(organizationId, runId),
            WORKFLOW_TYPE, TASK_QUEUE, 7, "c".repeat(64)
        );
    }

    private InvestigationWorkflowStartRequest withMaxRounds(
        InvestigationWorkflowStartRequest request,
        int maxRounds
    ) {
        return new InvestigationWorkflowStartRequest(
            request.organizationId(),
            request.projectId(),
            request.incidentId(),
            request.runId(),
            request.actorId(),
            maxRounds,
            request.maxToolCalls(),
            request.maxEvidenceItems(),
            request.maxTokens(),
            request.startedAt(),
            request.deadlineAt(),
            request.temporalClusterId(),
            request.temporalNamespace(),
            request.workflowId(),
            request.workflowType(),
            request.taskQueue(),
            request.authorizationRevision(),
            request.requestDigest()
        );
    }

    @WorkflowInterface
    public interface TestInvestigationWorkflow {
        @WorkflowMethod(name = WORKFLOW_TYPE)
        void run(InvestigationWorkflowStartRequest request);
    }

    public static final class TestInvestigationWorkflowImpl
        implements TestInvestigationWorkflow {
        @Override
        public void run(InvestigationWorkflowStartRequest request) {
            // Completing still exercises REJECT_DUPLICATE against closed history.
        }
    }
}
