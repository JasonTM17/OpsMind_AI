package ai.opsmind.toolgateway.domain;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EvidenceEnvelope(
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
    @JsonProperty("redacted_fields") int redactedFields,
    boolean truncated,
    @JsonProperty("artifact_reference") String artifactReference
) {
    public EvidenceEnvelope {
        content = content == null ? Map.of() : Map.copyOf(content);
    }
}
