package ai.opsmind.toolgateway.domain;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolExecutionResponse(
    @JsonProperty("execution_id") UUID executionId,
    ToolOutcome status,
    List<EvidenceEnvelope> evidence,
    @JsonProperty("denial_code") DenialCode denialCode,
    @JsonProperty("audit_event_id") UUID auditEventId,
    @JsonProperty("request_digest") String requestDigest,
    @JsonProperty("manifest_version") String manifestVersion,
    @JsonProperty("source_provenance") String sourceProvenance,
    @JsonProperty("redaction_count") int redactionCount,
    boolean truncated,
    boolean duplicate
) {
    public ToolExecutionResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public ToolExecutionResponse asDuplicate(UUID duplicateAuditEventId) {
        return new ToolExecutionResponse(
            executionId,
            ToolOutcome.DUPLICATE,
            evidence,
            denialCode,
            duplicateAuditEventId,
            requestDigest,
            manifestVersion,
            sourceProvenance,
            redactionCount,
            truncated,
            true
        );
    }
}
