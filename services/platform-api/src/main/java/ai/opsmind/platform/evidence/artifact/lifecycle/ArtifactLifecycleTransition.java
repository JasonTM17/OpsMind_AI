package ai.opsmind.platform.evidence.artifact.lifecycle;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactLifecycleState;

/** Metadata-only transition result suitable for persistence/audit by a later repository adapter. */
public record ArtifactLifecycleTransition(
    UUID artifactId, EvidenceArtifactLifecycleState fromState,
    EvidenceArtifactLifecycleState toState, long lifecycleVersion,
    UUID actorId, String reason, Instant occurredAt, boolean idempotent
) { }
