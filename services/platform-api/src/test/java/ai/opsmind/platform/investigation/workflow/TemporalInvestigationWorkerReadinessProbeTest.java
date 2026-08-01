package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import io.grpc.Status;
import io.temporal.api.common.v1.WorkerVersionCapabilities;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.taskqueue.v1.PollerInfo;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.serviceclient.WorkflowServiceStubs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class TemporalInvestigationWorkerReadinessProbeTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final InvestigationWorkflowProperties WORKFLOW =
        new InvestigationWorkflowProperties(
            "temporal-primary", "opsmind-prod", InvestigationWorkflow.TYPE,
            "opsmind-investigation-prod"
        );
    private static final InvestigationTemporalClientProperties CLIENT =
        new InvestigationTemporalClientProperties(
            "temporal-primary", "temporal.example.test:7233", true, false,
            Duration.ofSeconds(5), "opsmind-worker", "opsmind-worker-v1",
            Duration.ofSeconds(30), Duration.ofSeconds(5)
        );

    @Test
    void freshExactPollerOnWorkflowQueueIsReady() {
        StubbedProbe stubbed = probeWith(poller(
            "opsmind-worker", "opsmind-worker-v1", NOW.minusSeconds(30)
        ));

        assertThat(stubbed.probe().hasCompatibleWorkflowPoller(CLIENT, WORKFLOW))
            .isTrue();
        DescribeTaskQueueRequest request = stubbed.request().getValue();
        assertThat(request.getNamespace()).isEqualTo("opsmind-prod");
        assertThat(request.getTaskQueue().getName())
            .isEqualTo("opsmind-investigation-prod");
        assertThat(request.getTaskQueueType())
            .isEqualTo(TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW);
        assertThat(request.getReportPollers()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("incompatiblePollers")
    void incompatibleOrUnfreshPollerIsNotReady(PollerInfo poller) {
        StubbedProbe stubbed = probeWith(poller);

        assertThat(stubbed.probe().hasCompatibleWorkflowPoller(CLIENT, WORKFLOW))
            .isFalse();
    }

    @Test
    void rpcFailurePropagatesForAdmissionToFailClosed() {
        WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
        WorkflowServiceBlockingStub blocking = mock(WorkflowServiceBlockingStub.class);
        when(stubs.blockingStub()).thenReturn(blocking);
        when(blocking.describeTaskQueue(any()))
            .thenThrow(Status.UNAVAILABLE.asRuntimeException());

        TemporalInvestigationWorkerReadinessProbe probe =
            new TemporalInvestigationWorkerReadinessProbe(stubs, fixedClock());

        assertThatThrownBy(() -> probe.hasCompatibleWorkflowPoller(CLIENT, WORKFLOW))
            .isInstanceOf(RuntimeException.class);
    }

    private static Stream<PollerInfo> incompatiblePollers() {
        return Stream.of(
            poller("wrong-worker", "opsmind-worker-v1", NOW),
            poller("opsmind-worker", "wrong-build", NOW),
            PollerInfo.newBuilder()
                .setIdentity("opsmind-worker")
                .setWorkerVersionCapabilities(version("opsmind-worker-v1"))
                .build(),
            PollerInfo.newBuilder()
                .setIdentity("opsmind-worker")
                .setLastAccessTime(Timestamps.fromMillis(NOW.toEpochMilli()))
                .build(),
            poller("opsmind-worker", "opsmind-worker-v1", NOW.minusSeconds(31)),
            poller("opsmind-worker", "opsmind-worker-v1", NOW.plusSeconds(6)),
            PollerInfo.newBuilder()
                .setIdentity("opsmind-worker")
                .setWorkerVersionCapabilities(version("opsmind-worker-v1"))
                .setLastAccessTime(Timestamp.newBuilder().setSeconds(Long.MAX_VALUE))
                .build()
        );
    }

    private StubbedProbe probeWith(PollerInfo poller) {
        WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
        WorkflowServiceBlockingStub blocking = mock(WorkflowServiceBlockingStub.class);
        ArgumentCaptor<DescribeTaskQueueRequest> request =
            ArgumentCaptor.forClass(DescribeTaskQueueRequest.class);
        when(stubs.blockingStub()).thenReturn(blocking);
        when(blocking.describeTaskQueue(request.capture())).thenReturn(
            DescribeTaskQueueResponse.newBuilder().addPollers(poller).build()
        );
        return new StubbedProbe(
            new TemporalInvestigationWorkerReadinessProbe(stubs, fixedClock()),
            request
        );
    }

    private static PollerInfo poller(String identity, String buildId, Instant lastAccess) {
        return PollerInfo.newBuilder()
            .setIdentity(identity)
            .setWorkerVersionCapabilities(version(buildId))
            .setLastAccessTime(Timestamps.fromMillis(lastAccess.toEpochMilli()))
            .build();
    }

    private static WorkerVersionCapabilities version(String buildId) {
        return WorkerVersionCapabilities.newBuilder().setBuildId(buildId).build();
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private record StubbedProbe(
        TemporalInvestigationWorkerReadinessProbe probe,
        ArgumentCaptor<DescribeTaskQueueRequest> request
    ) {
    }
}
