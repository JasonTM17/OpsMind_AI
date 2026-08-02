package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

final class TemporalWorkerTestApplication {

    private TemporalWorkerTestApplication() {
    }

    static ConfigurableApplicationContext start(
        String target,
        String clusterId,
        String namespace,
        String taskQueue,
        String identity,
        String buildId
    ) {
        SpringApplication application = InvestigationTemporalWorkerApplication.createApplication();
        String[] arguments = {
            "--opsmind.investigation.temporal-client.cluster-id=" + clusterId,
            "--opsmind.investigation.temporal-client.target=" + target,
            "--opsmind.investigation.temporal-client.tls-enabled=false",
            "--opsmind.investigation.temporal-client.allow-local-cleartext=true",
            "--opsmind.investigation.temporal-client.rpc-timeout=PT5S",
            "--opsmind.investigation.temporal-client.required-worker-identity=" + identity,
            "--opsmind.investigation.temporal-client.required-worker-build-id=" + buildId,
            "--opsmind.investigation.temporal-client.required-worker-poller-max-age=PT30S",
            "--opsmind.investigation.temporal-client.required-worker-poller-future-skew=PT5S",
            "--opsmind.investigation.workflow.cluster-id=" + clusterId,
            "--opsmind.investigation.workflow.namespace=" + namespace,
            "--opsmind.investigation.workflow.workflow-type=" + InvestigationWorkflow.TYPE,
            "--opsmind.investigation.workflow.task-queue=" + taskQueue,
            "--opsmind.investigation.temporal-worker.enabled=true",
            "--opsmind.investigation.temporal-worker.identity=" + identity,
            "--opsmind.investigation.temporal-worker.build-id=" + buildId,
            "--opsmind.investigation.temporal-worker.max-concurrent-workflow-task-executors=8",
            "--opsmind.investigation.temporal-worker.max-concurrent-workflow-task-pollers=2",
            "--opsmind.investigation.temporal-worker.shutdown-timeout=PT5S",
        };
        ConfigurableApplicationContext context = application.run(arguments);
        assertThat(context.getBean(InvestigationTemporalWorkerRuntime.class).isRunning()).isTrue();
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
        return context;
    }
}
