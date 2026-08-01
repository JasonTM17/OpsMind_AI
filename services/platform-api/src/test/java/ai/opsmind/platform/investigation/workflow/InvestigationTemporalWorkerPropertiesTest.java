package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class InvestigationTemporalWorkerPropertiesTest {

    private static final InvestigationWorkflowProperties WORKFLOW =
        new InvestigationWorkflowProperties(
            "temporal-primary", "opsmind-prod", InvestigationWorkflow.TYPE,
            "opsmind-investigation-prod"
        );
    private static final InvestigationTemporalClientProperties CLIENT =
        new InvestigationTemporalClientProperties(
            "temporal-primary", "temporal.example.test:7233", true, false,
            Duration.ofSeconds(5), "opsmind-worker", "opsmind-worker-v1"
        );

    @Test
    void exactBoundedConfigurationIsAccepted() {
        assertThatCode(() -> properties(
            "opsmind-worker", "opsmind-worker-v1", 32, 5, Duration.ofSeconds(10)
        ).validate(CLIENT, WORKFLOW)).doesNotThrowAnyException();
    }

    @Test
    void defaultOffAndMismatchedIdentityBuildOrBoundsAreRejected() {
        assertRejected(new InvestigationTemporalWorkerProperties(
            false, "opsmind-worker", "opsmind-worker-v1", 32, 5,
            Duration.ofSeconds(10)
        ));
        assertRejected(properties(
            "wrong-worker", "opsmind-worker-v1", 32, 5, Duration.ofSeconds(10)
        ));
        assertRejected(properties(
            "opsmind-worker", "wrong-build", 32, 5, Duration.ofSeconds(10)
        ));
        assertRejected(properties(
            "opsmind-worker", "opsmind-worker-v1", 129, 5, Duration.ofSeconds(10)
        ));
        assertRejected(properties(
            "opsmind-worker", "opsmind-worker-v1", 4, 5, Duration.ofSeconds(10)
        ));
        assertRejected(properties(
            "opsmind-worker", "opsmind-worker-v1", 32, 5, Duration.ofSeconds(31)
        ));
    }

    @Test
    void nonCanonicalWorkflowTypeIsRejected() {
        InvestigationWorkflowProperties wrongType = new InvestigationWorkflowProperties(
            "temporal-primary", "opsmind-prod", "opsmind-investigation-v2",
            "opsmind-investigation-prod"
        );

        assertThatThrownBy(() -> properties(
            "opsmind-worker", "opsmind-worker-v1", 32, 5, Duration.ofSeconds(10)
        ).validate(CLIENT, wrongType)).isInstanceOf(IllegalStateException.class);
    }

    private static InvestigationTemporalWorkerProperties properties(
        String identity,
        String buildId,
        int executors,
        int pollers,
        Duration shutdownTimeout
    ) {
        return new InvestigationTemporalWorkerProperties(
            true, identity, buildId, executors, pollers, shutdownTimeout
        );
    }

    private static void assertRejected(InvestigationTemporalWorkerProperties properties) {
        assertThatThrownBy(() -> properties.validate(CLIENT, WORKFLOW))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Temporal worker configuration is outside policy.");
    }
}
