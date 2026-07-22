package ai.opsmind.platform.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
class IncidentRuntimeValues {

    private final Clock clock;

    IncidentRuntimeValues() {
        this(Clock.systemUTC());
    }

    IncidentRuntimeValues(Clock clock) {
        this.clock = clock;
    }

    Instant now() {
        // PostgreSQL timestamptz stores microseconds. Normalize once at the
        // runtime boundary so mutation, audit, outbox, replay, and subsequent
        // reads share the same canonical timestamp bytes.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    UUID newId() {
        return UUID.randomUUID();
    }
}
