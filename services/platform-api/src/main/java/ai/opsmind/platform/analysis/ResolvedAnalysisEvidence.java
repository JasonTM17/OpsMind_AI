package ai.opsmind.platform.analysis;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Tenant-authorized, classified, and redacted evidence prepared inside the trust boundary. */
public record ResolvedAnalysisEvidence(
    String prompt,
    String promptVersion,
    List<AnalysisEvidenceReference> contextRefs,
    List<String> dataClassifications
) {
    private static final Pattern PROMPT_VERSION = Pattern.compile("prompt-[a-z0-9-]+-v\\d+");
    private static final Set<String> DATA_CLASSES = Set.of(
        "redacted_metrics",
        "redacted_log_summary",
        "redacted_incident_summary",
        "raw_log",
        "secret",
        "personal_data"
    );

    public ResolvedAnalysisEvidence {
        if (prompt == null || prompt.isEmpty()
            || prompt.codePointCount(0, prompt.length()) > 32_768
            || promptVersion == null || !PROMPT_VERSION.matcher(promptVersion).matches()) {
            throw new IllegalArgumentException("Resolved analysis prompt is invalid.");
        }
        if (contextRefs == null || contextRefs.size() > 200
            || contextRefs.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Resolved analysis references are invalid.");
        }
        contextRefs = List.copyOf(contextRefs);
        if (dataClassifications == null || dataClassifications.isEmpty()
            || dataClassifications.size() > 5
            || dataClassifications.stream().anyMatch(java.util.Objects::isNull)
            || new HashSet<>(dataClassifications).size() != dataClassifications.size()
            || !DATA_CLASSES.containsAll(dataClassifications)) {
            throw new IllegalArgumentException("Resolved analysis classifications are invalid.");
        }
        dataClassifications = List.copyOf(dataClassifications);
    }
}
