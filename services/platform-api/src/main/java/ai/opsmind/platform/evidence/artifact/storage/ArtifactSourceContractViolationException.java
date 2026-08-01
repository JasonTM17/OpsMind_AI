package ai.opsmind.platform.evidence.artifact.storage;

import java.io.IOException;

/** Signals a proven source-length or digest mismatch after object I/O may have begun. */
final class ArtifactSourceContractViolationException extends IOException {

    ArtifactSourceContractViolationException(String message) {
        super(message);
    }
}
