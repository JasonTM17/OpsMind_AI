package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class InvestigationWorkflowReconcilerPropertiesTest {

    @Test
    void defaultsEnforceOneHourAgeAndBoundedBackoff() {
        InvestigationWorkflowReconcilerProperties properties = defaults();

        assertThatCode(() -> properties.validate(Duration.ofSeconds(5)))
            .doesNotThrowAnyException();
        assertThat(properties.maximumAttempts()).isEqualTo(8);
        assertThat(properties.retryDelay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.retryDelay(7)).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.retryDelay(20)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void twoRpcCallsAndSettlementMarginMustFitStrictlyInsideLease() {
        InvestigationWorkflowReconcilerProperties properties = properties(
            Duration.ofSeconds(15),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofDays(30),
            Duration.ofDays(1)
        );

        assertThatThrownBy(() -> properties.validate(Duration.ofSeconds(5)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retentionMustCoverHandoffReconciliationAndSafetyWindows() {
        InvestigationWorkflowReconcilerProperties properties = properties(
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofHours(3),
            Duration.ofHours(1)
        );

        assertThatThrownBy(() -> properties.validate(Duration.ofSeconds(5)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readAttemptBudgetCannotExceedEight() {
        InvestigationWorkflowReconcilerProperties properties =
            new InvestigationWorkflowReconcilerProperties(
                true,
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                9,
                Duration.ofHours(1),
                Duration.ofHours(1),
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                Duration.ofSeconds(10),
                Duration.ofDays(30),
                Duration.ofDays(1)
            );

        assertThatThrownBy(() -> properties.validate(Duration.ofSeconds(5)))
            .isInstanceOf(IllegalStateException.class);
    }

    private InvestigationWorkflowReconcilerProperties defaults() {
        return new InvestigationWorkflowReconcilerProperties(
            false, null, null, null, 0, null, null, null, null, null, null, null
        );
    }

    private InvestigationWorkflowReconcilerProperties properties(
        Duration lease,
        Duration settlement,
        Duration absence,
        Duration retention,
        Duration safety
    ) {
        return new InvestigationWorkflowReconcilerProperties(
            true,
            Duration.ofSeconds(1),
            lease,
            settlement,
            8,
            Duration.ofHours(1),
            Duration.ofHours(1),
            Duration.ofSeconds(1),
            Duration.ofMinutes(1),
            absence,
            retention,
            safety
        );
    }
}
