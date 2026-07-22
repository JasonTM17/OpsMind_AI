package ai.opsmind.platform.evidence;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Verified, bounded Tool Gateway evidence awaiting authoritative persistence. */
public record CollectedEvidence(
    UUID executionId,
    UUID gatewayAuditEventId,
    String gatewayRequestDigest,
    String sourceType,
    String source,
    String targetIdentity,
    Instant observedAt,
    Instant windowStart,
    Instant windowEnd,
    String connectorVersion,
    String manifestVersion,
    String policyVersion,
    String sourceProvenance,
    String trustClass,
    String contentDigest,
    String canonicalContent,
    int redactedFields,
    boolean truncated,
    String artifactReference,
    boolean gatewayDuplicate
) {

    private static final Pattern HEX_DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CONTENT_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern SAFE_METADATA = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:@/-]*");
    private static final Set<String> SOURCE_TYPES = Set.of(
        "metric", "log_summary", "trace", "change", "runbook"
    );
    private static final Set<String> TRUST_CLASSES = Set.of(
        "synthetic", "source-attested", "derived"
    );

    public CollectedEvidence {
        if (executionId == null || gatewayAuditEventId == null
            || !matches(HEX_DIGEST, gatewayRequestDigest)
            || !SOURCE_TYPES.contains(sourceType)
            || !safe(source, 128) || !safe(targetIdentity, 256)
            || observedAt == null || windowStart == null || windowEnd == null
            || windowEnd.isBefore(windowStart)
            || !safe(connectorVersion, 128) || !safe(manifestVersion, 128)
            || !safe(policyVersion, 128) || !safe(sourceProvenance, 256)
            || !TRUST_CLASSES.contains(trustClass)
            || !matches(CONTENT_DIGEST, contentDigest)
            || canonicalContent == null || canonicalContent.isBlank()
            || canonicalContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > EvidenceContentCanonicalizer.MAXIMUM_BYTES
            || redactedFields < 0 || artifactReference != null) {
            throw new IllegalArgumentException("Collected evidence metadata is invalid.");
        }
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static boolean safe(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength
            && SAFE_METADATA.matcher(value).matches();
    }
}
