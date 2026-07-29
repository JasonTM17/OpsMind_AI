package ai.opsmind.platform.evidence.artifact;

import java.util.HexFormat;
import java.util.regex.Pattern;

/** Canonical content digest value; raw artifact bytes never enter this type. */
public record EvidenceArtifactDigest(String value) {

    private static final Pattern SHA_256 = Pattern.compile("sha256:[0-9a-f]{64}");

    public EvidenceArtifactDigest {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException("Artifact digest must be canonical lowercase SHA-256.");
        }
    }

    public static EvidenceArtifactDigest parse(String value) {
        return new EvidenceArtifactDigest(value);
    }

    public byte[] bytes() {
        return HexFormat.of().parseHex(value.substring("sha256:".length()));
    }

    public String hexadecimal() {
        return value.substring("sha256:".length());
    }
}
