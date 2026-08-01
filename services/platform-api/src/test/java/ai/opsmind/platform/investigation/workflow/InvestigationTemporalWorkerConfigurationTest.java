package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

import io.temporal.testing.TestWorkflowEnvironment;
import org.flywaydb.core.Flyway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InvestigationTemporalWorkerConfigurationTest {

    private final ApplicationContextRunner contextRunner =
        new ApplicationContextRunner()
            .withUserConfiguration(InvestigationTemporalWorkerConfiguration.class);

    @Test
    void workerIsAbsentByDefaultAndApplicationIsNonWeb() {
        assertThat(InvestigationTemporalWorkerApplication.createApplication()
            .getWebApplicationType()).isEqualTo(WebApplicationType.NONE);

        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(InvestigationTemporalWorkerRuntime.class);
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
        });
    }

    @Test
    void enabledContextContainsOnlyWorkerTemporalInfrastructure() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            environment.start();
            InvestigationWorkflowProperties workflow = workflow(environment);
            InvestigationTemporalClientProperties client = client();
            InvestigationTemporalWorkerProperties worker = worker();
            InvestigationTemporalWorkerRuntime runtime =
                new InvestigationTemporalWorkerRuntime(
                    environment.getWorkflowServiceStubs(), client, workflow, worker
                );

            contextRunner
                .withBean(InvestigationTemporalWorkerRuntime.class, () -> runtime)
                .withPropertyValues(
                    "opsmind.investigation.temporal-worker.enabled=true",
                    "opsmind.investigation.temporal-worker.identity=opsmind-worker",
                    "opsmind.investigation.temporal-worker.build-id=opsmind-worker-v1",
                    "opsmind.investigation.temporal-worker.max-concurrent-workflow-task-executors=8",
                    "opsmind.investigation.temporal-worker.max-concurrent-workflow-task-pollers=2",
                    "opsmind.investigation.temporal-worker.shutdown-timeout=1s"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(
                        InvestigationTemporalWorkerRuntime.class
                    );
                    assertThat(context.getBean(InvestigationTemporalWorkerRuntime.class)
                        .isRunning()).isTrue();
                    assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
                    assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
                    assertThat(prohibitedApplicationBeans(context)).isEmpty();
                });
        }
    }

    private static List<String> prohibitedApplicationBeans(
        org.springframework.context.ApplicationContext context
    ) {
        List<String> prohibitedPackages = List.of(
            "ai.opsmind.platform.investigation.application",
            "ai.opsmind.platform.investigation.integration",
            "ai.opsmind.platform.analysis",
            "ai.opsmind.platform.delegation",
            "ai.opsmind.platform.messaging",
            "org.springframework.web"
        );
        return List.of(context.getBeanDefinitionNames()).stream()
            .filter(name -> {
                Class<?> type = context.getType(name);
                return type != null && prohibitedPackages.stream().anyMatch(
                    prefix -> type.getName().startsWith(prefix)
                );
            })
            .toList();
    }

    private static InvestigationWorkflowProperties workflow(
        TestWorkflowEnvironment environment
    ) {
        return new InvestigationWorkflowProperties(
            "temporal-test", environment.getNamespace(), InvestigationWorkflow.TYPE,
            "opsmind-investigation-worker-test"
        );
    }

    private static InvestigationTemporalClientProperties client() {
        return new InvestigationTemporalClientProperties(
            "temporal-test", "127.0.0.1:7233", false, true,
            Duration.ofSeconds(5), "opsmind-worker", "opsmind-worker-v1"
        );
    }

    private static InvestigationTemporalWorkerProperties worker() {
        return new InvestigationTemporalWorkerProperties(
            true, "opsmind-worker", "opsmind-worker-v1", 8, 2,
            Duration.ofSeconds(1)
        );
    }
}
