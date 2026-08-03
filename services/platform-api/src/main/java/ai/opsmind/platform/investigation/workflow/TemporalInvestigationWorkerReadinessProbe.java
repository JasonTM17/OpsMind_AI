package ai.opsmind.platform.investigation.workflow;

import java.time.Clock;
import java.time.Instant;

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;

import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.taskqueue.v1.PollerInfo;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;

public final class TemporalInvestigationWorkerReadinessProbe
    implements InvestigationWorkerReadinessProbe {

    private final WorkflowServiceStubs serviceStubs;
    private final Clock clock;

    public TemporalInvestigationWorkerReadinessProbe(
        WorkflowServiceStubs serviceStubs,
        Clock clock
    ) {
        this.serviceStubs = serviceStubs;
        this.clock = clock;
    }

    @Override
    public boolean hasCompatibleWorkflowPoller(
        InvestigationTemporalClientProperties clientProperties,
        InvestigationWorkflowProperties workflowProperties
    ) {
        clientProperties.validate(workflowProperties);
        DescribeTaskQueueResponse response = serviceStubs.blockingStub().describeTaskQueue(
            DescribeTaskQueueRequest.newBuilder()
                .setNamespace(workflowProperties.namespace())
                .setTaskQueue(TaskQueue.newBuilder().setName(workflowProperties.taskQueue()).build())
                .setTaskQueueType(TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW)
                .setReportPollers(true)
                .build()
        );
        return response.getPollersList().stream().anyMatch(
            poller -> compatible(poller, clientProperties)
        );
    }

    private boolean compatible(
        PollerInfo poller,
        InvestigationTemporalClientProperties properties
    ) {
        return properties.requiredWorkerIdentity().equals(poller.getIdentity())
            && poller.hasWorkerVersionCapabilities()
            && properties.requiredWorkerBuildId().equals(
                poller.getWorkerVersionCapabilities().getBuildId()
            )
            && fresh(poller, properties);
    }

    private boolean fresh(
        PollerInfo poller,
        InvestigationTemporalClientProperties properties
    ) {
        if (!poller.hasLastAccessTime()) {
            return false;
        }
        Timestamp timestamp = poller.getLastAccessTime();
        if (!Timestamps.isValid(timestamp)) {
            return false;
        }
        Instant lastAccess = Instant.ofEpochSecond(
            timestamp.getSeconds(), timestamp.getNanos()
        );
        Instant now = clock.instant();
        return !lastAccess.isBefore(now.minus(properties.requiredWorkerPollerMaxAge()))
            && !lastAccess.isAfter(now.plus(properties.requiredWorkerPollerFutureSkew()));
    }
}
