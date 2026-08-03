package ai.opsmind.platform.investigation.workflow;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;

import org.springframework.context.SmartLifecycle;

public final class InvestigationTemporalWorkerRuntime implements SmartLifecycle, AutoCloseable {

    private final InvestigationTemporalWorkerProperties workerProperties;
    private final WorkflowServiceStubs serviceStubs;
    private final WorkerFactory workerFactory;
    private final boolean ownsServiceStubs;
    private volatile boolean running;

    public InvestigationTemporalWorkerRuntime(
        InvestigationTemporalClientProperties clientProperties,
        InvestigationWorkflowProperties workflowProperties,
        InvestigationTemporalWorkerProperties workerProperties
    ) {
        this(
            InvestigationTemporalClientConfiguration.createServiceStubs(
                clientProperties, workflowProperties
            ),
            clientProperties,
            workflowProperties,
            workerProperties,
            true
        );
    }

    InvestigationTemporalWorkerRuntime(
        WorkflowServiceStubs serviceStubs,
        InvestigationTemporalClientProperties clientProperties,
        InvestigationWorkflowProperties workflowProperties,
        InvestigationTemporalWorkerProperties workerProperties
    ) {
        this(serviceStubs, clientProperties, workflowProperties, workerProperties, false);
    }

    private InvestigationTemporalWorkerRuntime(
        WorkflowServiceStubs serviceStubs,
        InvestigationTemporalClientProperties clientProperties,
        InvestigationWorkflowProperties workflowProperties,
        InvestigationTemporalWorkerProperties workerProperties,
        boolean ownsServiceStubs
    ) {
        workerProperties.validate(clientProperties, workflowProperties);
        this.workerProperties = workerProperties;
        this.serviceStubs = serviceStubs;
        this.ownsServiceStubs = ownsServiceStubs;

        WorkflowClient workflowClient = WorkflowClient.newInstance(
            serviceStubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(workflowProperties.namespace())
                .setIdentity(workerProperties.identity())
                .build()
        );
        workerFactory = WorkerFactory.newInstance(workflowClient);
        Worker worker = workerFactory.newWorker(
            workflowProperties.taskQueue(),
            WorkerOptions.newBuilder()
                .setIdentity(workerProperties.identity())
                .setBuildId(workerProperties.buildId())
                .setMaxConcurrentWorkflowTaskExecutionSize(
                    workerProperties.maxConcurrentWorkflowTaskExecutors()
                )
                .setMaxConcurrentWorkflowTaskPollers(
                    workerProperties.maxConcurrentWorkflowTaskPollers()
                )
                // Bounded sticky routing makes a crashed worker's parked workflow
                // eligible for replay by a replacement quickly, without version routing.
                .setStickyQueueScheduleToStartTimeout(Duration.ofSeconds(1))
                .build()
        );
        worker.registerWorkflowImplementationTypes(ParkedInvestigationWorkflow.class);
    }

    @Override
    public synchronized void start() {
        if (!running) {
            workerFactory.start();
            running = true;
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        long deadlineNanos = System.nanoTime() + workerProperties.shutdownTimeout().toNanos();
        workerFactory.shutdown();
        if (!awaitWorkerTermination(deadlineNanos)) {
            workerFactory.shutdownNow();
            if (!awaitWorkerTermination(deadlineNanos)) {
                throw new IllegalStateException(
                    "Temporal worker did not terminate before its shutdown deadline."
                );
            }
        }
        running = false;
    }

    private boolean awaitWorkerTermination(long deadlineNanos) {
        long remainingNanos = Math.max(0, deadlineNanos - System.nanoTime());
        workerFactory.awaitTermination(
            TimeUnit.NANOSECONDS.toMillis(remainingNanos), TimeUnit.MILLISECONDS
        );
        return workerFactory.isTerminated();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        stop();
        // WorkflowClient shares this transport and has no separate close lifecycle.
        if (ownsServiceStubs) {
            serviceStubs.shutdownNow();
        }
    }
}
