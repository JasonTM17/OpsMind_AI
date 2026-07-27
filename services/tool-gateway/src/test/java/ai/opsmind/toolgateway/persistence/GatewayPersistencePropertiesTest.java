package ai.opsmind.toolgateway.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class GatewayPersistencePropertiesTest {

    @Test
    void leaseMustCoverTheLongestEnabledActionAndFinalizationMargin() {
        GatewayPersistenceProperties unsafe = new GatewayPersistenceProperties(
            true,
            131_072,
            Duration.ofMillis(100)
        );
        GatewayPersistenceProperties safe = new GatewayPersistenceProperties(
            true,
            131_072,
            Duration.ofSeconds(30)
        );

        assertThatThrownBy(() -> unsafe.validateEnabled(Duration.ofSeconds(5)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not cover");
        assertThatCode(() -> safe.validateEnabled(Duration.ofSeconds(5)))
            .doesNotThrowAnyException();
    }

    @Test
    void migrationOnlyProfileMayHaveNoEnabledAction() {
        GatewayPersistenceProperties properties = new GatewayPersistenceProperties(
            true,
            131_072,
            Duration.ofSeconds(30)
        );

        assertThatCode(() -> properties.validateEnabled(Duration.ZERO))
            .doesNotThrowAnyException();
    }
}
