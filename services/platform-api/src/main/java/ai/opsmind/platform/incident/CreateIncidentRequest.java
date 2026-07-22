package ai.opsmind.platform.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateIncidentRequest(
    @NotBlank @Size(max = 160) String title,
    @NotBlank @Size(max = 4000) String summary,
    @NotNull IncidentSeverity severity,
    @NotBlank @Size(max = 1000) String reason
) {
}
