package ai.opsmind.platform.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = TransitionIncidentRequestDeserializer.class)
public record TransitionIncidentRequest(
    @NotNull IncidentStatus targetStatus,
    @NotBlank @Size(max = 1000) String reason,
    @Size(max = 8000) String rootCause,
    @Size(max = 8000) String resolutionSummary
) {
}
