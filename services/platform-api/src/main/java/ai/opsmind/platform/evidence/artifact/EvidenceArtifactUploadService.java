package ai.opsmind.platform.evidence.artifact;

import java.util.UUID;

import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectProbe;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectStored;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactObjectStorage;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactStorageException;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactStorageProperties;
import ai.opsmind.platform.evidence.artifact.storage.ManagedArtifactSource;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Default-off application control plane that brackets object I/O with separate authorization transactions. */
@Service
@ConditionalOnProperty(
    name = {"opsmind.persistence.enabled", "opsmind.evidence.artifact.storage.enabled"},
    havingValue = "true"
)
public final class EvidenceArtifactUploadService {

    private final IncidentAnalysisAuthorizer authorizer;
    private final EvidenceArtifactUploadRepository repository;
    private final EvidenceArtifactObjectStorage storage;
    private final EvidenceArtifactStorageProperties storageProperties;
    private final EvidenceArtifactUploadSettlementCoordinator settlements;

    public EvidenceArtifactUploadService(
        IncidentAnalysisAuthorizer authorizer,
        EvidenceArtifactUploadRepository repository,
        EvidenceArtifactObjectStorage storage,
        EvidenceArtifactStorageProperties storageProperties
    ) {
        this.authorizer = authorizer;
        this.repository = repository;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.settlements = new EvidenceArtifactUploadSettlementCoordinator(authorizer, repository);
    }

    public EvidenceArtifactUploadResult upload(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID artifactId,
        ManagedArtifactSource content
    ) {
        if (artifactId == null || content == null) {
            if (content != null) storage.release(content);
            throw new IllegalArgumentException("Artifact upload identity and content are required.");
        }
        EvidenceArtifactUploadClaim claim;
        try {
            claim = authorizer.withAnalyzeAccess(
                principal, organizationId, projectId, incidentId,
                scope -> repository.claim(
                    scope,
                    artifactId,
                    UUID.randomUUID(),
                    storageProperties.uploadLeaseDuration()
                )
            );
        } catch (RuntimeException | Error failure) {
            storage.release(content);
            throw failure;
        }
        if (claim.artifact().expectedByteCount() > storageProperties.maximumObjectBytes()) {
            storage.release(content);
            settlements.settle(principal, organizationId, projectId, incidentId, claim,
                EvidenceArtifactUploadOutcome.FAILED, null, "artifact.object-too-large");
            throw EvidenceArtifactUploadProblems.objectTooLarge();
        }
        try {
            requireObjectIoOutsideTransaction();
        } catch (RuntimeException | Error failure) {
            storage.release(content);
            throw failure;
        }
        ArtifactObjectStored stored;
        try {
            try {
                stored = probeThenStore(claim, content);
            } finally {
                storage.release(content);
            }
        }
        catch (EvidenceArtifactStorageException exception) {
            return settlements.failStorage(
                principal, organizationId, projectId, incidentId, claim, exception
            );
        }
        catch (RuntimeException exception) {
            settlements.settleAfterObjectFailure(
                principal, organizationId, projectId, incidentId, claim,
                EvidenceArtifactUploadOutcome.UNCERTAIN, "artifact.storage-uncertain", exception
            );
            throw EvidenceArtifactUploadProblems.uncertain(exception);
        }
        if (!matchesExpectation(claim, stored)) {
            settlements.settle(principal, organizationId, projectId, incidentId, claim,
                EvidenceArtifactUploadOutcome.ORPHANED, null, "artifact.object-mismatch");
            throw EvidenceArtifactUploadProblems.orphaned();
        }
        EvidenceArtifactUploadSettlement settlement = settlements.settle(
            principal, organizationId, projectId, incidentId, claim,
            EvidenceArtifactUploadOutcome.STORED, stored, null
        );
        if (!settlement.isStored()) throw EvidenceArtifactUploadProblems.finalizationRejected();
        return EvidenceArtifactUploadResult.from(claim, settlement);
    }

    private ArtifactObjectStored probeThenStore(
        EvidenceArtifactUploadClaim claim,
        ManagedArtifactSource content
    ) {
        if (!claim.probeRequired()) {
            return storage.putIfAbsent(
                claim.expectation(),
                content,
                claim.uploadLeaseExpiresAt()
            );
        }
        ArtifactObjectProbe probe = storage.probe(claim.expectation());
        if (probe instanceof ArtifactObjectProbe.Match match) return match.object();
        if (probe instanceof ArtifactObjectProbe.Absent) {
            return storage.putIfAbsent(
                claim.expectation(),
                content,
                claim.uploadLeaseExpiresAt()
            );
        }
        return null;
    }

    private static boolean matchesExpectation(
        EvidenceArtifactUploadClaim claim,
        ArtifactObjectStored stored
    ) {
        return stored != null && stored.digest().equals(claim.artifact().expectedDigest())
            && stored.byteCount() == claim.artifact().expectedByteCount();
    }

    private static void requireObjectIoOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Artifact object I/O must not run inside a transaction.");
        }
    }

}
