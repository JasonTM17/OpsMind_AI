package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.junit.jupiter.api.Test;

class TemporalInvestigationWorkflowAdmissionTest {

    private final InvestigationWorkflowProperties workflow =
        new InvestigationWorkflowProperties(
            "temporal-primary", "opsmind-prod", "opsmind-investigation-v1",
            "opsmind-investigation-prod"
        );
    private final InvestigationTemporalClientProperties client =
        new InvestigationTemporalClientProperties(
            "temporal-primary", "temporal.example.test:7233", true, false,
            Duration.ofSeconds(5), "opsmind-worker", "opsmind-worker-v1"
        );

    @Test
    void compatiblePollerOpensAdmission() {
        InvestigationWorkerReadinessProbe probe = mock(InvestigationWorkerReadinessProbe.class);
        when(probe.hasCompatibleWorkflowPoller(client, workflow)).thenReturn(true);

        assertThatCode(() -> new TemporalInvestigationWorkflowAdmission(client, probe)
            .requireReady(workflow)).doesNotThrowAnyException();
    }

    @Test
    void missingOrFailedPollerClosesAdmissionWithSafeProblem() {
        InvestigationWorkerReadinessProbe probe = mock(InvestigationWorkerReadinessProbe.class);
        when(probe.hasCompatibleWorkflowPoller(client, workflow))
            .thenThrow(new IllegalStateException("raw server detail"));

        assertThatThrownBy(() -> new TemporalInvestigationWorkflowAdmission(client, probe)
            .requireReady(workflow))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                assertThat(exception.code()).isEqualTo("investigation.workflow-not-ready");
                assertThat(exception.getMessage()).doesNotContain("raw server detail");
            });
    }
}
