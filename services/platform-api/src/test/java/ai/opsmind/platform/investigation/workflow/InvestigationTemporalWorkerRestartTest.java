package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.serviceclient.WorkflowServiceStubs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "OPSMIND_PHASE9_TEMPORAL_INTEGRATION",
    matches = "true"
)
class InvestigationTemporalWorkerRestartTest {

    private static final String CLUSTER_ID = "temporal-phase-09";
    private static final String NAMESPACE = "default";
    private static final String TASK_QUEUE = "opsmind-investigation-worker-restart-test";
    private static final String IDENTITY = "opsmind-phase-09-worker";
    private static final String BUILD_ID = "opsmind-phase-09-worker-v1";
    private static final String START_DIGEST = "a".repeat(64);

    @Test
    void parkedWorkflowReplaysOnRealTemporalAfterWorkerRestartAndCancelsWithoutLeaks()
        throws Exception {
        InvestigationWorkflowProperties workflow = new InvestigationWorkflowProperties(
            CLUSTER_ID, NAMESPACE, InvestigationWorkflow.TYPE, TASK_QUEUE
        );
        InvestigationTemporalClientProperties client = new InvestigationTemporalClientProperties(
            CLUSTER_ID, temporalTarget(), false, true, Duration.ofSeconds(5),
            IDENTITY, BUILD_ID, Duration.ofSeconds(30), Duration.ofSeconds(5)
        );
        InvestigationTemporalWorkerProperties worker = new InvestigationTemporalWorkerProperties(
            true, IDENTITY, BUILD_ID, 8, 2, Duration.ofSeconds(5)
        );
        WorkflowServiceStubs controlStubs =
            InvestigationTemporalClientConfiguration.createServiceStubs(client, workflow);
        try {
            WorkflowClient controlClient = WorkflowClient.newInstance(
                controlStubs,
                WorkflowClientOptions.newBuilder().setNamespace(NAMESPACE).build()
            );
            InvestigationWorkflowStartRequest request = request();
            InvestigationWorkflowClient.StartResult started;

            try (InvestigationTemporalWorkerRuntime firstWorker =
                new InvestigationTemporalWorkerRuntime(client, workflow, worker)) {
                firstWorker.start();
                awaitReady(controlStubs, client, workflow);
                started = new TemporalInvestigationWorkflowClient(
                    controlClient, client, workflow
                ).start(request, START_DIGEST);
                awaitWorkflowTaskCount(
                    controlClient, request, started, EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED, 1
                );
            }

            try (InvestigationTemporalWorkerRuntime replacementWorker =
                new InvestigationTemporalWorkerRuntime(client, workflow, worker)) {
                replacementWorker.start();
                awaitReady(controlStubs, client, workflow);
                WorkflowStub stub = controlClient.newUntypedWorkflowStub(
                    execution(request, started), Optional.of(InvestigationWorkflow.TYPE)
                );
                stub.cancel("phase-09-test-cleanup");
                awaitWorkflowTaskCount(
                    controlClient, request, started, EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED, 2
                );
                assertThatThrownBy(() -> stub.getResult(10, TimeUnit.SECONDS, Void.class))
                    .isInstanceOfSatisfying(WorkflowFailedException.class, failure ->
                        assertThat(failure.getCause()).isInstanceOf(CanceledFailure.class)
                    );
            }

            List<HistoryEvent> history = history(controlClient, request, started);
            assertThat(eventCount(history, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED))
                .isOne();
            assertThat(eventCount(history, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED))
                .isOne();
            assertThat(history).noneMatch(event -> event.getEventType().name().contains("ACTIVITY"));
            TemporalWorkflowHistoryCanaryAssertions.assertNoProhibitedContent(history);
        }
        finally {
            controlStubs.shutdownNow();
        }
    }

    private static void awaitReady(
        WorkflowServiceStubs stubs,
        InvestigationTemporalClientProperties client,
        InvestigationWorkflowProperties workflow
    ) throws InterruptedException {
        TemporalInvestigationWorkerReadinessProbe probe =
            new TemporalInvestigationWorkerReadinessProbe(stubs, Clock.systemUTC());
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                if (probe.hasCompatibleWorkflowPoller(client, workflow)) {
                    return;
                }
            }
            catch (RuntimeException exception) {
                // The local server can accept TCP before its gRPC service is ready.
                lastFailure = exception;
            }
            Thread.sleep(100);
        }
        AssertionError timeout = new AssertionError(
            "Temporal worker did not advertise a fresh compatible poller."
        );
        if (lastFailure != null) {
            timeout.initCause(lastFailure);
        }
        throw timeout;
    }

    private static void awaitWorkflowTaskCount(
        WorkflowClient client,
        InvestigationWorkflowStartRequest request,
        InvestigationWorkflowClient.StartResult started,
        EventType eventType,
        long expectedCount
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 150; attempt++) {
            if (eventCount(history(client, request, started), eventType) >= expectedCount) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Temporal workflow did not make the expected restart progress.");
    }

    private static List<HistoryEvent> history(
        WorkflowClient client,
        InvestigationWorkflowStartRequest request,
        InvestigationWorkflowClient.StartResult started
    ) {
        return client.fetchHistory(request.workflowId(), started.temporalRunId()).getEvents();
    }

    private static long eventCount(List<HistoryEvent> history, EventType eventType) {
        return history.stream().filter(event -> event.getEventType() == eventType).count();
    }

    private static WorkflowExecution execution(
        InvestigationWorkflowStartRequest request,
        InvestigationWorkflowClient.StartResult started
    ) {
        return WorkflowExecution.newBuilder()
            .setWorkflowId(request.workflowId())
            .setRunId(started.temporalRunId())
            .build();
    }

    private static String temporalTarget() {
        String configured = System.getenv("OPSMIND_INVESTIGATION_TEMPORAL_TARGET");
        return configured == null || configured.isBlank() ? "127.0.0.1:7233" : configured;
    }

    private static InvestigationWorkflowStartRequest request() {
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
            CLUSTER_ID, NAMESPACE,
            InvestigationWorkflowStartRequest.workflowId(organizationId, runId),
            InvestigationWorkflow.TYPE, TASK_QUEUE, 7, "c".repeat(64)
        );
    }
}
