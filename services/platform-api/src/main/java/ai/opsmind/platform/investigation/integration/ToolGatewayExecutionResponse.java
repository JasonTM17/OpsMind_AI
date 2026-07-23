package ai.opsmind.platform.investigation.integration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

record ToolGatewayExecutionResponse(
    @JsonProperty("execution_id") UUID executionId,
    String status,
    List<EvidenceEnvelope> evidence,
    @JsonProperty("denial_code") String denialCode,
    @JsonProperty("audit_event_id") UUID auditEventId,
    @JsonProperty("request_digest") String requestDigest,
    @JsonProperty("manifest_version") String manifestVersion,
    @JsonProperty("source_provenance") String sourceProvenance,
    @JsonProperty("redaction_count") Integer redactionCount,
    Boolean truncated,
    Boolean duplicate
) {
    ToolGatewayExecutionResponse {
        evidence = evidence == null ? null : List.copyOf(evidence);
    }

    record EvidenceEnvelope(
        String source,
        @JsonProperty("target_identity") String targetIdentity,
        @JsonProperty("observed_at") Instant observedAt,
        @JsonProperty("window_start") Instant windowStart,
        @JsonProperty("window_end") Instant windowEnd,
        @JsonProperty("connector_version") String connectorVersion,
        @JsonProperty("manifest_version") String manifestVersion,
        @JsonProperty("trust_class") String trustClass,
        @JsonProperty("content_digest") String contentDigest,
        Map<String, Object> content,
        @JsonProperty("redacted_fields") Integer redactedFields,
        Boolean truncated,
        @JsonProperty("artifact_reference") String artifactReference
    ) {
        EvidenceEnvelope {
            content = content == null ? null : Map.copyOf(content);
        }
    }
}
