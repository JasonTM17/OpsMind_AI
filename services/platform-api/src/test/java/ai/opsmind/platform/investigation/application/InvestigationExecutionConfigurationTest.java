package ai.opsmind.platform.investigation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowAdmission;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowHandoffRepository;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InvestigationExecutionConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withPropertyValues("opsmind.investigation.enabled=true")
        .withBean(InvestigationOrchestrator.class, () -> mock(InvestigationOrchestrator.class))
        .withUserConfiguration(InvestigationExecutionConfiguration.class);

    @Test
    void defaultModePublishesExactlyOneInlineStarter() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).hasSingleBean(InvestigationExecutionStarter.class);
            assertThat(application.getBean(InvestigationExecutionStarter.class))
                .isInstanceOf(InlineInvestigationExecutionStarter.class);
        });
    }

    @Test
    void temporalModePublishesExactlyOneDurableStarterWhenAdmissionExists() {
        context
            .withPropertyValues(
                "opsmind.investigation.execution-mode=temporal",
                "opsmind.investigation.workflow-starter.enabled=true"
            )
            .withBean(
                InvestigationWorkflowAdmission.class,
                () -> mock(InvestigationWorkflowAdmission.class)
            )
            .withBean(
                InvestigationWorkflowHandoffRepository.class,
                () -> mock(InvestigationWorkflowHandoffRepository.class)
            )
            .run(application -> {
                assertThat(application).hasNotFailed();
                assertThat(application).hasSingleBean(InvestigationExecutionStarter.class);
                assertThat(application.getBean(InvestigationExecutionStarter.class))
                    .isInstanceOf(DurableInvestigationExecutionStarter.class);
            });
    }

    @Test
    void temporalModeWithoutWorkflowStarterFailsClosedAtStartup() {
        context
            .withPropertyValues("opsmind.investigation.execution-mode=temporal")
            .withBean(
                InvestigationWorkflowAdmission.class,
                () -> mock(InvestigationWorkflowAdmission.class)
            )
            .withBean(
                InvestigationWorkflowHandoffRepository.class,
                () -> mock(InvestigationWorkflowHandoffRepository.class)
            )
            .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void temporalModeRejectsRpcEnvelopeLongerThanItsLease() {
        context
            .withPropertyValues(
                "opsmind.investigation.execution-mode=temporal",
                "opsmind.investigation.workflow-starter.enabled=true",
                "opsmind.investigation.workflow-starter.lease-duration=PT5S",
                "opsmind.investigation.workflow-starter.rpc-safety-margin=PT1S",
                "opsmind.investigation.temporal-client.rpc-timeout=PT30S"
            )
            .withBean(
                InvestigationWorkflowAdmission.class,
                () -> mock(InvestigationWorkflowAdmission.class)
            )
            .withBean(
                InvestigationWorkflowHandoffRepository.class,
                () -> mock(InvestigationWorkflowHandoffRepository.class)
            )
            .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void temporalModeWithoutAdmissionFailsClosedAtStartup() {
        context
            .withPropertyValues(
                "opsmind.investigation.execution-mode=temporal",
                "opsmind.investigation.workflow-starter.enabled=true"
            )
            .withBean(
                InvestigationWorkflowHandoffRepository.class,
                () -> mock(InvestigationWorkflowHandoffRepository.class)
            )
            .run(application -> assertThat(application).hasFailed());
    }
}
