package ai.opsmind.platform.evidence.artifact;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;
import ai.opsmind.platform.evidence.artifact.lifecycle.ArtifactLifecycleCommand;
import ai.opsmind.platform.evidence.artifact.lifecycle.ArtifactLifecycleRepository;
import ai.opsmind.platform.evidence.artifact.lifecycle.ArtifactLifecycleTransition;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Analyze-authorized entry point for metadata creation; it intentionally accepts no artifact body. */
@Service
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public final class EvidenceArtifactMetadataService {

    private final IncidentAnalysisAuthorizer authorizer;
    private final EvidenceArtifactMetadataRepository repository;
    private final ArtifactLifecycleRepository lifecycleRepository;

    public EvidenceArtifactMetadataService(
        IncidentAnalysisAuthorizer authorizer,
        EvidenceArtifactMetadataRepository repository,
        ArtifactLifecycleRepository lifecycleRepository
    ) {
        this.authorizer = authorizer;
        this.repository = repository;
        this.lifecycleRepository = lifecycleRepository;
    }

    public EvidenceArtifactMetadata create(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        EvidenceArtifactCreateCommand command
    ) {
        if (command == null) throw new IllegalArgumentException("Artifact metadata command is required.");
        return authorizer.withAnalyzeAccess(
            principal,
            organizationId,
            projectId,
            incidentId,
            scope -> repository.create(scope, command, Instant.now().truncatedTo(ChronoUnit.MICROS))
        );
    }

    /** Resolves only lifecycle-eligible metadata; it never exposes an object reference or body. */
    public EvidenceArtifactMetadata requireReadableMetadata(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID artifactId
    ) {
        if (artifactId == null) throw new IllegalArgumentException("Artifact identifier is required.");
        return authorizer.withAnalyzeAccess(
            principal,
            organizationId,
            projectId,
            incidentId,
            scope -> repository.requireReadableMetadata(scope, artifactId)
        );
    }

    /** Persists one tenant-bound lifecycle transition and its event/audit pair atomically. */
    public ArtifactLifecycleTransition transition(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID artifactId,
        ArtifactLifecycleCommand command
    ) {
        if (artifactId == null || command == null) {
            throw new IllegalArgumentException("Artifact lifecycle identity and command are required.");
        }
        return authorizer.withAnalyzeAccess(
            principal, organizationId, projectId, incidentId,
            scope -> lifecycleRepository.transition(scope, artifactId, command)
        );
    }
}
