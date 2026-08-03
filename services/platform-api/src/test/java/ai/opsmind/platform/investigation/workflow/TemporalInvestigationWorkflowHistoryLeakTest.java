package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class TemporalInvestigationWorkflowHistoryLeakTest {

    private static final String WORKFLOW_TYPE = InvestigationWorkflow.TYPE;
    private static final String TASK_QUEUE = "opsmind-investigation-history-test";
    private static final String PAYLOAD_DIGEST = "a".repeat(64);

    @Test
    void startHistoryContainsOnlyTheApprovedBoundedContract() throws Exception {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(HistoryWorkflowImpl.class);
            environment.start();
            WorkflowClient sdkClient = environment.getWorkflowClient();
            InvestigationWorkflowProperties workflowProperties =
                new InvestigationWorkflowProperties(
                    "temporal-history-test",
                    sdkClient.getOptions().getNamespace(),
                    WORKFLOW_TYPE,
                    TASK_QUEUE
                );
            TemporalInvestigationWorkflowClient client =
                new TemporalInvestigationWorkflowClient(
                    sdkClient, clientProperties(), workflowProperties
                );
            InvestigationWorkflowStartRequest request =
                request(sdkClient.getOptions().getNamespace());

            InvestigationWorkflowClient.StartResult started =
                client.start(request, PAYLOAD_DIGEST);
            sdkClient.newUntypedWorkflowStub(
                WorkflowExecution.newBuilder()
                    .setWorkflowId(request.workflowId())
                    .setRunId(started.temporalRunId())
                    .build(),
                Optional.of(WORKFLOW_TYPE)
            ).getResult(Void.class);

            var history = sdkClient.fetchHistory(
                request.workflowId(), started.temporalRunId()
            );
            HistoryEvent first = history.getEvents().getFirst();
            assertThat(first.getEventType())
                .isEqualTo(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
            var attributes = first.getWorkflowExecutionStartedEventAttributes();
            assertThat(attributes.getWorkflowType().getName()).isEqualTo(WORKFLOW_TYPE);
            assertThat(attributes.getTaskQueue().getName()).isEqualTo(TASK_QUEUE);
            assertThat(attributes.getMemo().getFieldsMap())
                .containsKey(TemporalInvestigationWorkflowClient.PAYLOAD_DIGEST_MEMO_KEY);
            assertThat(attributes.getInput().getPayloadsCount()).isOne();

            String inputJson = attributes.getInput().getPayloads(0)
                .getData().toStringUtf8();
            var input = JsonMapper.builder().findAndAddModules().build()
                .readTree(inputJson);
            assertThat(input.propertyNames()).containsExactly(
                "organization_id",
                "project_id",
                "incident_id",
                "run_id",
                "actor_id",
                "max_rounds",
                "max_tool_calls",
                "max_evidence_items",
                "max_tokens",
                "started_at",
                "deadline_at",
                "temporal_cluster_id",
                "temporal_namespace",
                "workflow_id",
                "workflow_type",
                "task_queue",
                "authorization_revision",
                "request_digest"
            );
            assertThat(inputJson)
                .contains(
                    request.organizationId().toString(),
                    request.runId().toString(),
                    request.startedAt().toString(),
                    request.deadlineAt().toString(),
                    request.requestDigest()
                )
                .doesNotContain(
                    "history-prompt-canary",
                    "history-evidence-canary",
                    "history-bearer-canary",
                    "history-secret-canary",
                    "prompt",
                    "evidence_body",
                    "bearer_token",
                    "api_key",
                    "provider_request",
                    "capability_token",
                    "title",
                    "summary"
                );
        }
    }

    private InvestigationTemporalClientProperties clientProperties() {
        return new InvestigationTemporalClientProperties(
            "temporal-history-test",
            "127.0.0.1:7233",
            false,
            true,
            Duration.ofSeconds(5),
            "history-worker",
            "history-build"
        );
    }

    private InvestigationWorkflowStartRequest request(String namespace) {
        UUID organizationId =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID runId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        return new InvestigationWorkflowStartRequest(
            organizationId,
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            runId,
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            4,
            2,
            10,
            1_000,
            Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-01-01T00:10:00Z"),
            "temporal-history-test",
            namespace,
            InvestigationWorkflowStartRequest.workflowId(organizationId, runId),
            WORKFLOW_TYPE,
            TASK_QUEUE,
            7,
            "c".repeat(64)
        );
    }

    @WorkflowInterface
    public interface HistoryWorkflow {
        @WorkflowMethod(name = WORKFLOW_TYPE)
        void run(InvestigationWorkflowStartRequest request);
    }

    public static final class HistoryWorkflowImpl implements HistoryWorkflow {
        @Override
        public void run(InvestigationWorkflowStartRequest request) {
            // Completion keeps the history bounded while preserving the start event.
        }
    }
}
