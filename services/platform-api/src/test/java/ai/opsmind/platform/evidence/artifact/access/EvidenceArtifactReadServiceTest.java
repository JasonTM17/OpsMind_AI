package ai.opsmind.platform.evidence.artifact.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactLifecycleState;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadata;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadataReader;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectProbe;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectStored;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactObjectStorage;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisScope;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;

import org.junit.jupiter.api.Test;

class EvidenceArtifactReadServiceTest {
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID INCIDENT = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ARTIFACT = UUID.randomUUID();
    private static final EvidenceArtifactDigest DIGEST =
        EvidenceArtifactDigest.parse("sha256:" + "a".repeat(64));

    private final IncidentAnalysisAuthorizer authorizer = mock(IncidentAnalysisAuthorizer.class);
    private final EvidenceArtifactMetadataReader reader = mock(EvidenceArtifactMetadataReader.class);
    private final EvidenceArtifactObjectStorage storage = mock(EvidenceArtifactObjectStorage.class);
    private final EvidenceArtifactReadService service = new EvidenceArtifactReadService(
        authorizer, reader, storage, new AuthorizedArtifactReadService()
    );

    @Test
    void probesOnlyAfterRunBoundMetadataAuthorization() {
        scopeWork();
        when(reader.findVisible(scope(), RUN, ARTIFACT)).thenReturn(Optional.of(metadata()));
        when(storage.probe(any())).thenReturn(new ArtifactObjectProbe.Match(
            new ArtifactObjectStored(DIGEST, 4, "version-1", "kms-v1")
        ));

        assertThat(service.authorizeReadableObject(
            mock(OpsMindPrincipal.class), ORGANIZATION, PROJECT, INCIDENT, RUN, ARTIFACT, DIGEST
        )).isEqualTo(metadata());
    }

    @Test
    void absentObjectFailsWithNonEnumeratingContract() {
        scopeWork();
        when(reader.findVisible(scope(), RUN, ARTIFACT)).thenReturn(Optional.of(metadata()));
        when(storage.probe(any())).thenReturn(new ArtifactObjectProbe.Absent());

        assertThatThrownBy(() -> service.authorizeReadableObject(
            mock(OpsMindPrincipal.class), ORGANIZATION, PROJECT, INCIDENT, RUN, ARTIFACT, DIGEST
        )).isInstanceOfSatisfying(
            ai.opsmind.platform.common.api.PlatformProblemException.class,
            failure -> assertThat(failure.code()).isEqualTo("evidence-artifact.not-found")
        );
    }

    @SuppressWarnings("unchecked")
    private void scopeWork() {
        doAnswer(invocation -> ((Function<AuthorizedIncidentAnalysisScope, ?>)
            invocation.getArgument(4)).apply(scope()))
            .when(authorizer).withAnalyzeAccess(any(), any(), any(), any(), any());
    }

    private AuthorizedIncidentAnalysisScope scope() {
        return new AuthorizedIncidentAnalysisScope(ORGANIZATION, PROJECT, INCIDENT, ACTOR, 7L);
    }

    private EvidenceArtifactMetadata metadata() {
        return new EvidenceArtifactMetadata(
            ARTIFACT, ORGANIZATION, PROJECT, INCIDENT, RUN, ACTOR,
            UUID.fromString("a1600000-0000-4000-8000-000000000006"),
            "log", "source", "v1", "internal", DIGEST, 4, 7,
            "standard", "sg", "operator", EvidenceArtifactLifecycleState.AVAILABLE, 2,
            Instant.parse("2030-01-01T00:00:00Z")
        );
    }
}
