package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InvestigationTemporalObserverPropertiesTest {

    @Test
    void tlsObserverUsesSeparateCredentialAndRedactsIt() {
        InvestigationTemporalObserverProperties properties = properties();
        properties.setApiKey("synthetic-observer-credential");

        assertThatCode(() -> properties.validate(workflow()))
            .doesNotThrowAnyException();
        assertThat(properties.toString())
            .contains("apiKey=<redacted>")
            .doesNotContain("synthetic-observer-credential");
    }

    @Test
    void tlsObserverCannotStartWithoutItsCredential() {
        assertThatThrownBy(() -> properties().validate(workflow()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside policy");
    }

    private InvestigationTemporalObserverProperties properties() {
        InvestigationTemporalObserverProperties properties =
            new InvestigationTemporalObserverProperties();
        properties.setClusterId("temporal-test");
        properties.setTarget("temporal.example.test:7233");
        return properties;
    }

    private InvestigationWorkflowProperties workflow() {
        return new InvestigationWorkflowProperties(
            "temporal-test",
            "namespace-test",
            "opsmind-investigation-v1",
            "investigation-test"
        );
    }
}
