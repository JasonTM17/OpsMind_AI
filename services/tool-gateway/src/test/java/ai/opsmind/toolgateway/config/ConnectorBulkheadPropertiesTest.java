package ai.opsmind.toolgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConnectorBulkheadPropertiesTest {

    @Test
    void appliesBoundedDefaultsOnlyWhenPropertiesAreOmitted() {
        ConnectorBulkheadProperties properties = new ConnectorBulkheadProperties(null, null);

        assertThat(properties.globalConcurrency()).isEqualTo(32);
        assertThat(properties.perTenantConcurrency()).isEqualTo(4);
    }

    @ParameterizedTest
    @MethodSource("invalidBounds")
    void rejectsInvalidBounds(Integer globalConcurrency, Integer perTenantConcurrency) {
        assertThatThrownBy(
            () -> new ConnectorBulkheadProperties(globalConcurrency, perTenantConcurrency)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> invalidBounds() {
        return Stream.of(
            Arguments.of(0, 1),
            Arguments.of(-1, 1),
            Arguments.of(1_025, 1),
            Arguments.of(32, 0),
            Arguments.of(32, -1),
            Arguments.of(4, 5)
        );
    }
}
