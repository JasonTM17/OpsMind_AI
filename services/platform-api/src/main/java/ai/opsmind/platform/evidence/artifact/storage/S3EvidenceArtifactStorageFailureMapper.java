package ai.opsmind.platform.evidence.artifact.storage;

import java.io.IOException;

import software.amazon.awssdk.services.s3.model.S3Exception;

/** Converts provider failures into the stable storage-port taxonomy without exposing provider details. */
final class S3EvidenceArtifactStorageFailureMapper {

    private S3EvidenceArtifactStorageFailureMapper() { }

    static EvidenceArtifactStorageException putFailure(Throwable failure) {
        if (failure instanceof EvidenceArtifactStorageException storageException) {
            return storageException;
        }
        if (hasCause(failure, IOException.class)) {
            return failure(EvidenceArtifactStorageException.FailureKind.STREAM_REJECTED, true, failure);
        }
        if (failure instanceof S3Exception s3Exception) {
            return fromS3PutFailure(s3Exception);
        }
        return failure(EvidenceArtifactStorageException.FailureKind.OUTCOME_UNCERTAIN, true, failure);
    }

    static EvidenceArtifactStorageException probeFailure(Throwable failure) {
        if (failure instanceof S3Exception s3Exception && accessDenied(s3Exception.statusCode())) {
            return failure(EvidenceArtifactStorageException.FailureKind.ACCESS_DENIED, true, failure);
        }
        return failure(EvidenceArtifactStorageException.FailureKind.UNAVAILABLE, true, failure);
    }

    static boolean objectAbsent(Throwable failure) {
        return failure instanceof S3Exception s3Exception && s3Exception.statusCode() == 404;
    }

    static EvidenceArtifactStorageException streamRejected(boolean objectMayExist, Throwable cause) {
        return failure(EvidenceArtifactStorageException.FailureKind.STREAM_REJECTED, objectMayExist, cause);
    }

    static EvidenceArtifactStorageException sourceContractMismatch(Throwable cause) {
        return failure(EvidenceArtifactStorageException.FailureKind.SOURCE_CONTRACT_MISMATCH, true, cause);
    }

    private static EvidenceArtifactStorageException fromS3PutFailure(S3Exception failure) {
        int statusCode = failure.statusCode();
        if (accessDenied(statusCode)) {
            return failure(EvidenceArtifactStorageException.FailureKind.ACCESS_DENIED, false, failure);
        }
        if (statusCode == 409 || statusCode == 412) {
            return failure(EvidenceArtifactStorageException.FailureKind.IMMUTABLE_CONFLICT, true, failure);
        }
        if (statusCode == 408 || statusCode == 429 || statusCode >= 500) {
            return failure(EvidenceArtifactStorageException.FailureKind.OUTCOME_UNCERTAIN, true, failure);
        }
        return failure(EvidenceArtifactStorageException.FailureKind.UNAVAILABLE, false, failure);
    }

    private static boolean accessDenied(int statusCode) {
        return statusCode == 401 || statusCode == 403;
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    private static EvidenceArtifactStorageException failure(
        EvidenceArtifactStorageException.FailureKind kind,
        boolean objectMayExist,
        Throwable cause
    ) {
        return new EvidenceArtifactStorageException(kind, objectMayExist, cause);
    }
}
