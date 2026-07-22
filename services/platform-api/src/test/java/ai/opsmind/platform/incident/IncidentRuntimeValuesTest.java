package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

class IncidentRuntimeValuesTest {

    @Test
    void runtimeTimestampsUsePostgresMicrosecondPrecision() {
        Instant source = Instant.parse("2030-01-01T00:00:00.123456789Z");
        IncidentRuntimeValues values = new IncidentRuntimeValues(
            Clock.fixed(source, ZoneOffset.UTC)
        );

        assertThat(values.now()).isEqualTo(source.truncatedTo(ChronoUnit.MICROS));
    }
}
