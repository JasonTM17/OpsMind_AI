package ai.opsmind.toolgateway.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ToolExecutionRequest(
    @NotNull
    @JsonProperty("execution_id") UUID executionId,
    @NotNull
    @JsonProperty("tenant_id") UUID tenantId,
    @NotNull
    @JsonProperty("project_id") UUID projectId,
    @NotNull
    @JsonProperty("incident_id") UUID incidentId,
    @NotNull
    @JsonProperty("run_id") UUID runId,
    @NotBlank
    @JsonProperty("actor_subject") String actorSubject,
    @NotBlank
    String tool,
    @NotBlank
    String action,
    @NotBlank
    @JsonProperty("schema_version") String schemaVersion,
    @NotBlank
    String resource,
    @NotNull
    Map<String, Object> arguments,
    @NotNull
    @JsonProperty("deadline_at") Instant deadlineAt,
    @NotNull
    @Valid
    @JsonProperty("result_budget") ResultBudget resultBudget
) {
    public ToolExecutionRequest {
        arguments = arguments == null ? null : Map.copyOf(arguments);
    }

    public record ResultBudget(
        @Min(1)
        @JsonProperty("max_bytes") int maxBytes,
        @Min(1)
        @JsonProperty("max_items") int maxItems
    ) { }
}
