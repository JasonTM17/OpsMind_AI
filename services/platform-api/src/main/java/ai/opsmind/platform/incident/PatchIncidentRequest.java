package ai.opsmind.platform.incident;

import java.util.UUID;

import tools.jackson.databind.annotation.JsonDeserialize;

/** Closed incident metadata command with explicit field-presence semantics. */
@JsonDeserialize(using = PatchIncidentRequestDeserializer.class)
public final class PatchIncidentRequest {

    private final String title;
    private final boolean titlePresent;
    private final String summary;
    private final boolean summaryPresent;
    private final IncidentSeverity severity;
    private final boolean severityPresent;
    private final UUID ownerId;
    private final boolean ownerIdPresent;
    private final String reason;

    PatchIncidentRequest(
        String title,
        boolean titlePresent,
        String summary,
        boolean summaryPresent,
        IncidentSeverity severity,
        boolean severityPresent,
        UUID ownerId,
        boolean ownerIdPresent,
        String reason
    ) {
        this.title = title;
        this.titlePresent = titlePresent;
        this.summary = summary;
        this.summaryPresent = summaryPresent;
        this.severity = severity;
        this.severityPresent = severityPresent;
        this.ownerId = ownerId;
        this.ownerIdPresent = ownerIdPresent;
        this.reason = reason;
    }

    public String title() {
        return title;
    }

    public boolean hasTitle() {
        return titlePresent;
    }

    public String summary() {
        return summary;
    }

    public boolean hasSummary() {
        return summaryPresent;
    }

    public IncidentSeverity severity() {
        return severity;
    }

    public boolean hasSeverity() {
        return severityPresent;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public boolean hasOwnerId() {
        return ownerIdPresent;
    }

    public String reason() {
        return reason;
    }

    boolean hasMutation() {
        return titlePresent || summaryPresent || severityPresent || ownerIdPresent;
    }
}
