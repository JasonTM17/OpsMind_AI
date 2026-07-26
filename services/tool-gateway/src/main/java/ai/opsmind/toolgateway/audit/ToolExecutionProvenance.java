package ai.opsmind.toolgateway.audit;

import java.util.regex.Pattern;

/** Immutable identity of the manifest and connector selected for one tool execution. */
public record ToolExecutionProvenance(
    String tool,
    String action,
    String riskClass,
    String connectorId,
    String connectorProfile,
    String connectorManifestByteDigest
) {
    private static final Pattern SAFE_NAME =
        Pattern.compile("^[a-z0-9]+(?:[.-][a-z0-9]+)*$");
    private static final Pattern DIGEST = Pattern.compile("^sha256:[a-f0-9]{64}$");

    public ToolExecutionProvenance {
        if (!safe(tool) || !safe(action) || !safe(riskClass) || !safe(connectorId)
            || !safe(connectorProfile)
            || connectorManifestByteDigest == null
            || !DIGEST.matcher(connectorManifestByteDigest).matches()) {
            throw new IllegalArgumentException("Tool execution provenance is invalid.");
        }
    }

    private static boolean safe(String value) {
        return value != null && value.length() <= 128 && SAFE_NAME.matcher(value).matches();
    }
}
