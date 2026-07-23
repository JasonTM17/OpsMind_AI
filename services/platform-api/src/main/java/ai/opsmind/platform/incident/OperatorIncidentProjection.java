package ai.opsmind.platform.incident;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.common.api.OperatorDisplayRedactor;
import ai.opsmind.platform.common.api.OperatorProjection;

record OperatorIncidentProjection(
    UUID id,
    UUID organizationId,
    UUID projectId,
    String title,
    String summary,
    IncidentSeverity severity,
    IncidentStatus status,
    String rootCause,
    String resolutionSummary,
    UUID createdBy,
    UUID updatedBy,
    Instant createdAt,
    Instant updatedAt,
    long version
) {
    static OperatorProjection<OperatorIncidentProjection> from(IncidentResponse incident) {
        OperatorDisplayRedactor redactor = new OperatorDisplayRedactor();
        OperatorDisplayRedactor.Redaction title =
            redactor.redact(incident.title(), "Incident title withheld");
        OperatorDisplayRedactor.Redaction summary =
            redactor.redact(incident.summary(), "Incident summary withheld");
        OperatorDisplayRedactor.Redaction rootCause = nullable(
            redactor, incident.rootCause()
        );
        OperatorDisplayRedactor.Redaction resolution = nullable(
            redactor, incident.resolutionSummary()
        );
        int redactionCount = Math.addExact(
            Math.addExact(title.count(), summary.count()),
            Math.addExact(rootCause.count(), resolution.count())
        );
        return new OperatorProjection<>(
            new OperatorIncidentProjection(
                incident.id(), incident.organizationId(), incident.projectId(),
                required(title, "Incident title withheld"),
                required(summary, "Incident summary withheld"),
                incident.severity(), incident.status(),
                nullableValue(rootCause), nullableValue(resolution),
                incident.createdBy(), incident.updatedBy(),
                incident.createdAt(), incident.updatedAt(), incident.version()
            ),
            redactionCount
        );
    }

    private static OperatorDisplayRedactor.Redaction nullable(
        OperatorDisplayRedactor redactor,
        String value
    ) {
        return value == null
            ? new OperatorDisplayRedactor.Redaction("", 0)
            : redactor.redact(value, "Withheld by display policy");
    }

    private static String required(
        OperatorDisplayRedactor.Redaction redaction,
        String fallback
    ) {
        return redaction.value().isBlank() ? fallback : redaction.value();
    }

    private static String nullableValue(OperatorDisplayRedactor.Redaction redaction) {
        return redaction.value().isEmpty() ? null : required(redaction, "Withheld by display policy");
    }
}
