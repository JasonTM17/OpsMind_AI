package ai.opsmind.platform.evidence.artifact;

import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectStored;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactStorageException;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;

/** Persists post-object-I/O outcomes while retaining the original failure evidence. */
final class EvidenceArtifactUploadSettlementCoordinator {

    private final IncidentAnalysisAuthorizer authorizer;
    private final EvidenceArtifactUploadRepository repository;

    EvidenceArtifactUploadSettlementCoordinator(
        IncidentAnalysisAuthorizer authorizer,
        EvidenceArtifactUploadRepository repository
    ) {
        this.authorizer = authorizer;
        this.repository = repository;
    }

    EvidenceArtifactUploadResult failStorage(
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

    EvidenceArtifactUploadSettlement settle(
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

    EvidenceArtifactUploadSettlement settleAfterObjectFailure(
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

    private static boolean requiresOperatorReconciliation(EvidenceArtifactStorageException exception) {
        return exception.objectMayExist()
            && (exception.kind() == EvidenceArtifactStorageException.FailureKind.SOURCE_CONTRACT_MISMATCH
                || exception.kind() == EvidenceArtifactStorageException.FailureKind.REMOTE_METADATA_MISMATCH);
    }
}
