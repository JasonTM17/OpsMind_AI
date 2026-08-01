package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

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
        application.setDefaultProperties(Map.ofEntries(
            Map.entry("opsmind.investigation.temporal-client.cluster-id", clusterId),
            Map.entry("opsmind.investigation.temporal-client.target", target),
            Map.entry("opsmind.investigation.temporal-client.tls-enabled", "false"),
            Map.entry("opsmind.investigation.temporal-client.allow-local-cleartext", "true"),
            Map.entry("opsmind.investigation.temporal-client.rpc-timeout", "PT5S"),
            Map.entry("opsmind.investigation.temporal-client.required-worker-identity", identity),
            Map.entry("opsmind.investigation.temporal-client.required-worker-build-id", buildId),
            Map.entry("opsmind.investigation.temporal-client.required-worker-poller-max-age", "PT30S"),
            Map.entry("opsmind.investigation.temporal-client.required-worker-poller-future-skew", "PT5S"),
            Map.entry("opsmind.investigation.workflow.cluster-id", clusterId),
            Map.entry("opsmind.investigation.workflow.namespace", namespace),
            Map.entry("opsmind.investigation.workflow.workflow-type", InvestigationWorkflow.TYPE),
            Map.entry("opsmind.investigation.workflow.task-queue", taskQueue),
            Map.entry("opsmind.investigation.temporal-worker.enabled", "true"),
            Map.entry("opsmind.investigation.temporal-worker.identity", identity),
            Map.entry("opsmind.investigation.temporal-worker.build-id", buildId),
            Map.entry("opsmind.investigation.temporal-worker.max-concurrent-workflow-task-executors", "8"),
            Map.entry("opsmind.investigation.temporal-worker.max-concurrent-workflow-task-pollers", "2"),
            Map.entry("opsmind.investigation.temporal-worker.shutdown-timeout", "PT5S")
        ));
        ConfigurableApplicationContext context = application.run();
        assertThat(context.getBean(InvestigationTemporalWorkerRuntime.class).isRunning()).isTrue();
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
        return context;
    }
}
