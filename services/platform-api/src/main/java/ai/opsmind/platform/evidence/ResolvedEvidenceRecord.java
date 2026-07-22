package ai.opsmind.platform.evidence;

import java.time.Instant;
import java.util.UUID;

/** Authorized evidence projection safe for redacted AI prompt assembly. */
public record ResolvedEvidenceRecord(
    UUID evidenceId,
    UUID runId,
    String digest,
    String sourceType,
    String source,
    String targetIdentity,
    Instant observedAt,
    String trustClass,
    String canonicalContent,
    boolean truncated
) { }
