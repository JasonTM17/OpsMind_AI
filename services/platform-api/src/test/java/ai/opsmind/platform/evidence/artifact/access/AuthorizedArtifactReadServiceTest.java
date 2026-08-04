package ai.opsmind.platform.evidence.artifact.access;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactLifecycleState;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadata;

import org.junit.jupiter.api.Test;

class AuthorizedArtifactReadServiceTest {
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID INCIDENT = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final EvidenceArtifactDigest DIGEST =
        new EvidenceArtifactDigest("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    @Test
    void authorizesOnlyMatchingReadableMetadataAndObject() {
        var service = new AuthorizedArtifactReadService();
        assertDoesNotThrow(() -> service.authorize(metadata(EvidenceArtifactLifecycleState.AVAILABLE), request(),
            new ArtifactObjectProbeFacts(true, DIGEST, 3)));
        assertThrows(ArtifactAccessDeniedException.class,
            () -> service.authorize(metadata(EvidenceArtifactLifecycleState.TOMBSTONED), request(),
                new ArtifactObjectProbeFacts(true, DIGEST, 3)));
        assertThrows(ArtifactAccessDeniedException.class,
            () -> service.authorize(metadata(EvidenceArtifactLifecycleState.AVAILABLE), request(),
                new ArtifactObjectProbeFacts(false, null, 0)));
        var foreign = new AuthorizedArtifactReadRequest(UUID.randomUUID(), PROJECT, INCIDENT, RUN, ACTOR, 7, DIGEST);
        assertThrows(ArtifactAccessDeniedException.class,
            () -> service.authorize(metadata(EvidenceArtifactLifecycleState.AVAILABLE), foreign,
                new ArtifactObjectProbeFacts(true, DIGEST, 3)));
        assertThrows(ArtifactAccessDeniedException.class,
            () -> service.authorize(metadata(EvidenceArtifactLifecycleState.AVAILABLE), request(),
                new ArtifactObjectProbeFacts(true, DIGEST, 4)));
    }

    private static AuthorizedArtifactReadRequest request() {
        return new AuthorizedArtifactReadRequest(ORG, PROJECT, INCIDENT, RUN, ACTOR, 7, DIGEST);
    }

    static EvidenceArtifactMetadata metadata(EvidenceArtifactLifecycleState state) {
        return new EvidenceArtifactMetadata(UUID.randomUUID(), ORG, PROJECT, INCIDENT, RUN, ACTOR,
            UUID.randomUUID(), "log", "source", "v1", "internal", DIGEST, 3, 7,
            "standard", "sg", "operator", state, 2, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
