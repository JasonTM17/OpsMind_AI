package ai.opsmind.platform.evidence.artifact;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.http.HttpStatus;

/** Safe public problem mapping for artifact upload control-plane failures. */
final class EvidenceArtifactUploadProblems {

    private EvidenceArtifactUploadProblems() { }

    static PlatformProblemException objectTooLarge() {
        return new PlatformProblemException(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "evidence-artifact.object-too-large",
            "The evidence artifact exceeds the configured upload limit."
        );
    }

    static PlatformProblemException failed(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "evidence-artifact.upload-failed",
            "The artifact upload could not be completed safely.",
            cause
        );
    }

    static PlatformProblemException uncertain(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "evidence-artifact.upload-uncertain",
            "The artifact upload outcome could not be verified safely.",
            cause
        );
    }

    static PlatformProblemException orphaned() {
        return new PlatformProblemException(
            HttpStatus.CONFLICT,
            "evidence-artifact.upload-orphaned",
            "The artifact upload could not be verified safely."
        );
    }

    static PlatformProblemException orphaned(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.CONFLICT,
            "evidence-artifact.upload-orphaned",
            "The artifact upload could not be verified safely.",
            cause
        );
    }

    static PlatformProblemException finalizationRejected() {
        return new PlatformProblemException(
            HttpStatus.CONFLICT,
            "evidence-artifact.finalization-rejected",
            "The artifact upload could not be finalized safely."
        );
    }

    static PlatformProblemException settlementFailed(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "evidence-artifact.settlement-failed",
            "The artifact upload outcome could not be persisted safely.",
            cause
        );
    }
}
