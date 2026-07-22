package ai.opsmind.platform.analysis;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AnalysisEvidenceReference(
    @JsonProperty("evidence_id") UUID evidenceId,
    String digest,
    @JsonProperty("source_type") String sourceType
) {
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Set<String> SOURCE_TYPES = Set.of(
        "metric", "log_summary", "incident_summary", "trace", "change", "runbook"
    );

    public AnalysisEvidenceReference {
        if (evidenceId == null || digest == null || !DIGEST.matcher(digest).matches()
            || !SOURCE_TYPES.contains(sourceType)) {
            throw new IllegalArgumentException("Analysis evidence reference is invalid.");
        }
    }
}
