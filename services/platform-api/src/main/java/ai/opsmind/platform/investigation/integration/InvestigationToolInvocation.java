package ai.opsmind.platform.investigation.integration;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Server-owned executable template selected by an untrusted model intent. */
public record InvestigationToolInvocation(
    String connector,
    String operation,
    String argumentsDigest,
    String tool,
    String action,
    String schemaVersion,
    String resource,
    Map<String, Object> arguments,
    int maximumBytes,
    int maximumItems,
    Duration maximumDuration,
    String requiredRole,
    String policyVersion,
    String expectedManifestVersion
) {

    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:@/-]*");
    private static final Set<String> ARGUMENT_NAMES = Set.of("service", "metric", "max_points");

    public InvestigationToolInvocation {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        if (!"metrics".equals(connector) || !"query".equals(operation)
            || argumentsDigest == null || !DIGEST.matcher(argumentsDigest).matches()
            || !"observability".equals(tool) || !"metrics.query".equals(action)
            || !"1.0".equals(schemaVersion) || !safe(resource, 256)
            || !ARGUMENT_NAMES.equals(arguments.keySet())
            || !safe(arguments.get("service"), 128) || !safe(arguments.get("metric"), 128)
            || !(arguments.get("max_points") instanceof Integer maximumPoints)
            || maximumPoints < 1 || maximumPoints > 100
            || maximumBytes < 1 || maximumBytes > 65_536
            || maximumItems < 1 || maximumItems > 100
            || maximumDuration == null || maximumDuration.isNegative()
            || maximumDuration.isZero() || maximumDuration.compareTo(Duration.ofSeconds(30)) > 0
            || !"operator:read".equals(requiredRole) || !safe(policyVersion, 64)
            || !safe(expectedManifestVersion, 128)) {
            throw new IllegalArgumentException("Investigation tool invocation is invalid.");
        }
    }

    public String canonicalAction() {
        return tool + ":" + action + ":" + schemaVersion;
    }

    private static boolean safe(Object value, int maximumLength) {
        return value instanceof String text && !text.isBlank()
            && text.length() <= maximumLength && SAFE.matcher(text).matches();
    }
}
