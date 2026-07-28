package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class InvestigationWorkflowStarterPropertiesTest {

    @Test
    void retryBackoffIsDeterministicAndBounded() {
        InvestigationWorkflowStarterProperties properties = properties();

        assertThat(properties.retryDelay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.retryDelay(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.retryDelay(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(properties.retryDelay(20)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void leaseMarginAndBatchBoundsFailClosed() {
        InvestigationWorkflowStarterProperties invalid =
            new InvestigationWorkflowStarterProperties(
                true, Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofSeconds(1),
                Duration.ofMinutes(1), 8, 25, 101
            );

        assertThatThrownBy(invalid::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside policy");
    }

    private InvestigationWorkflowStarterProperties properties() {
        return new InvestigationWorkflowStarterProperties(
            true, Duration.ofSeconds(1), Duration.ofSeconds(30),
            Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofSeconds(1),
            Duration.ofMinutes(1), 8, 25, 10
        );
    }
}
