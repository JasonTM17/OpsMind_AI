package ai.opsmind.platform.evidence.artifact;

import java.io.InputStream;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectProbe;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectStored;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactObjectStorage;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactStorageException;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactStorageProperties;
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
    }

    public EvidenceArtifactUploadResult upload(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID artifactId,
        InputStream content
    ) {
        if (artifactId == null || content == null) {
            throw new IllegalArgumentException("Artifact upload identity and content are required.");
        }
        EvidenceArtifactUploadClaim claim = authorizer.withAnalyzeAccess(
            principal, organizationId, projectId, incidentId,
            scope -> repository.claim(scope, artifactId, UUID.randomUUID(), storageProperties.uploadLeaseDuration())
        );
        if (claim.reconciliationRequired()) throw EvidenceArtifactUploadProblems.orphaned();
        if (claim.artifact().expectedByteCount() > storageProperties.maximumObjectBytes()) {
            settle(principal, organizationId, projectId, incidentId, claim,
                EvidenceArtifactUploadOutcome.FAILED, null, "artifact.object-too-large");
            throw EvidenceArtifactUploadProblems.objectTooLarge();
        }
        requireObjectIoOutsideTransaction();
        ArtifactObjectStored stored;
        try {
            stored = probeThenStore(claim, content);
        }
        catch (EvidenceArtifactStorageException exception) {
            return failStorage(principal, organizationId, projectId, incidentId, claim, exception);
        }
        catch (RuntimeException exception) {
            settleAfterObjectFailure(
                principal, organizationId, projectId, incidentId, claim,
                EvidenceArtifactUploadOutcome.UNCERTAIN, "artifact.storage-uncertain", exception
            );
            throw EvidenceArtifactUploadProblems.uncertain(exception);
        }
        if (!matchesExpectation(claim, stored)) {
            settle(principal, organizationId, projectId, incidentId, claim,
                EvidenceArtifactUploadOutcome.ORPHANED, null, "artifact.object-mismatch");
            throw EvidenceArtifactUploadProblems.orphaned();
        }
        EvidenceArtifactUploadSettlement settlement = settle(
            principal, organizationId, projectId, incidentId, claim,
            EvidenceArtifactUploadOutcome.STORED, stored, null
        );
        if (!settlement.isStored()) throw EvidenceArtifactUploadProblems.finalizationRejected();
        return EvidenceArtifactUploadResult.from(claim, settlement);
    }

    private ArtifactObjectStored probeThenStore(EvidenceArtifactUploadClaim claim, InputStream content) {
        if (!claim.probeRequired()) return storage.putIfAbsent(claim.expectation(), content);
        ArtifactObjectProbe probe = storage.probe(claim.expectation());
        if (probe instanceof ArtifactObjectProbe.Match match) return match.object();
        if (probe instanceof ArtifactObjectProbe.Absent) {
            return storage.putIfAbsent(claim.expectation(), content);
        }
        return null;
    }

    private EvidenceArtifactUploadResult failStorage(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        EvidenceArtifactUploadClaim claim,
        EvidenceArtifactStorageException exception
    ) {
        if (requiresOperatorReconciliation(exception)) {
            settleAfterObjectFailure(
                principal, organizationId, projectId, incidentId, claim,
                EvidenceArtifactUploadOutcome.ORPHANED, "artifact.object-mismatch", exception
            );
            throw EvidenceArtifactUploadProblems.orphaned(exception);
        }
        EvidenceArtifactUploadOutcome outcome = exception.objectMayExist()
            ? EvidenceArtifactUploadOutcome.UNCERTAIN : EvidenceArtifactUploadOutcome.FAILED;
        settleAfterObjectFailure(
            principal, organizationId, projectId, incidentId, claim, outcome,
            outcome == EvidenceArtifactUploadOutcome.UNCERTAIN
                ? "artifact.storage-uncertain" : "artifact.storage-failed",
            exception
        );
        throw exception.objectMayExist()
            ? EvidenceArtifactUploadProblems.uncertain(exception)
            : EvidenceArtifactUploadProblems.failed(exception);
    }

    private static boolean requiresOperatorReconciliation(EvidenceArtifactStorageException exception) {
        return exception.objectMayExist()
            && (exception.kind() == EvidenceArtifactStorageException.FailureKind.SOURCE_CONTRACT_MISMATCH
                || exception.kind() == EvidenceArtifactStorageException.FailureKind.REMOTE_METADATA_MISMATCH);
    }

    private EvidenceArtifactUploadSettlement settle(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        EvidenceArtifactUploadClaim claim,
        EvidenceArtifactUploadOutcome outcome,
        ArtifactObjectStored stored,
        String failureCode
    ) {
        return authorizer.withAnalyzeAccess(
            principal, organizationId, projectId, incidentId,
            scope -> repository.settle(scope, claim, outcome, stored, failureCode)
        );
    }

    private EvidenceArtifactUploadSettlement settleAfterObjectFailure(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        EvidenceArtifactUploadClaim claim,
        EvidenceArtifactUploadOutcome outcome,
        String failureCode,
        Throwable objectFailure
    ) {
        try {
            return settle(
                principal, organizationId, projectId, incidentId,
                claim, outcome, null, failureCode
            );
        } catch (PlatformProblemException settlementFailure) {
            settlementFailure.addSuppressed(objectFailure);
            throw settlementFailure;
        } catch (RuntimeException settlementFailure) {
            PlatformProblemException classifiedFailure =
                EvidenceArtifactUploadProblems.settlementFailed(settlementFailure);
            classifiedFailure.addSuppressed(objectFailure);
            throw classifiedFailure;
        }
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
