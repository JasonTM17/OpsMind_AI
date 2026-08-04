package ai.opsmind.platform.evidence.artifact.access;

import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadata;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadataReader;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactStorageKey;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectExpectation;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectProbe;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactObjectStorage;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactStorageException;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Authorizes metadata in-transaction, then probes object integrity outside that transaction. */
@Service
@ConditionalOnProperty(
    prefix = "opsmind.evidence.artifact.storage",
    name = "enabled",
    havingValue = "true"
)
@ConditionalOnProperty(
    prefix = "opsmind.persistence",
    name = "enabled",
    havingValue = "true"
)
public final class EvidenceArtifactReadService {

    private final IncidentAnalysisAuthorizer authorizer;
    private final EvidenceArtifactMetadataReader metadataReader;
    private final EvidenceArtifactObjectStorage storage;
    private final AuthorizedArtifactReadService readGate;

    public EvidenceArtifactReadService(
        IncidentAnalysisAuthorizer authorizer,
        EvidenceArtifactMetadataReader metadataReader,
        EvidenceArtifactObjectStorage storage,
        AuthorizedArtifactReadService readGate
    ) {
        this.authorizer = authorizer;
        this.metadataReader = metadataReader;
        this.storage = storage;
        this.readGate = readGate;
    }

    /** Returns only authorized metadata. Stream opening remains a later adapter contract. */
    public EvidenceArtifactMetadata authorizeReadableObject(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID runId,
        UUID artifactId,
        EvidenceArtifactDigest expectedDigest
    ) {
        if (runId == null || artifactId == null || expectedDigest == null) throw hidden();
        AuthorizedReadCandidate candidate = authorizer.withAnalyzeAccess(
            principal, organizationId, projectId, incidentId,
            scope -> new AuthorizedReadCandidate(
                metadataReader.findVisible(scope, runId, artifactId).orElseThrow(this::hidden),
                new AuthorizedArtifactReadRequest(
                    scope.organizationId(), scope.projectId(), scope.incidentId(), runId,
                    scope.actorId(), scope.authorizationEpoch(), expectedDigest
                )
            )
        );
        ArtifactObjectProbe probe;
        try {
            EvidenceArtifactMetadata metadata = candidate.metadata();
            probe = storage.probe(new ArtifactObjectExpectation(
                metadata.artifactId(),
                EvidenceArtifactStorageKey.derive(
                    metadata.organizationId(), metadata.artifactId(), metadata.expectedDigest()
                ),
                metadata.expectedDigest(), metadata.expectedByteCount()
            ));
        }
        catch (EvidenceArtifactStorageException exception) {
            throw new PlatformProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "evidence-artifact.storage-unavailable",
                "Evidence artifact storage is temporarily unavailable.",
                exception
            );
        }
        try {
            return readGate.authorize(
                candidate.metadata(), candidate.request(), probeFacts(probe)
            );
        }
        catch (ArtifactAccessDeniedException exception) {
            throw hidden();
        }
    }

    private ArtifactObjectProbeFacts probeFacts(ArtifactObjectProbe probe) {
        if (probe instanceof ArtifactObjectProbe.Match match) {
            return new ArtifactObjectProbeFacts(
                true, match.object().digest(), match.object().byteCount()
            );
        }
        return new ArtifactObjectProbeFacts(false, null, 0);
    }

    private PlatformProblemException hidden() {
        return new PlatformProblemException(
            HttpStatus.NOT_FOUND,
            "evidence-artifact.not-found",
            "Evidence artifact was not found or is not visible."
        );
    }

    private record AuthorizedReadCandidate(
        EvidenceArtifactMetadata metadata,
        AuthorizedArtifactReadRequest request
    ) { }
}
