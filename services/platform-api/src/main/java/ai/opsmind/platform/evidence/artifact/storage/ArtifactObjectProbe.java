package ai.opsmind.platform.evidence.artifact.storage;

/** HEAD-only classification used before any retry can issue another write. */
public sealed interface ArtifactObjectProbe {

    record Absent() implements ArtifactObjectProbe { }

    record Match(ArtifactObjectStored object) implements ArtifactObjectProbe {
        public Match {
            if (object == null) throw new IllegalArgumentException("Matched artifact object is required.");
        }
    }

    record Mismatch() implements ArtifactObjectProbe { }
}
