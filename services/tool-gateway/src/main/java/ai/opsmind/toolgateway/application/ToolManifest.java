package ai.opsmind.toolgateway.application;

import java.time.Duration;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

public record ToolManifest(
    String tool,
    String action,
    String schemaVersion,
    String manifestVersion,
    String connectorId,
    boolean enabled,
    boolean readOnly,
    String requestSchemaId,
    String riskClass,
    String requiredRole,
    String resourcePrefix,
    String credentialProfile,
    Duration maximumDuration,
    int maximumBytes,
    int maximumItems,
    Set<String> allowedArgumentNames,
    Set<String> egressTargets,
    String redactionClass,
    String auditClass
) {
    public ToolManifest {
        allowedArgumentNames = Set.copyOf(allowedArgumentNames);
        egressTargets = Set.copyOf(egressTargets);
        if (blank(tool) || blank(action) || blank(schemaVersion) || blank(manifestVersion)
            || blank(connectorId) || blank(requiredRole) || blank(resourcePrefix)
            || blank(credentialProfile) || blank(auditClass) || blank(requestSchemaId) || !readOnly
            || !requestSchemaId.startsWith("https://contracts.opsmind.invalid/tool-gateway/")
            || !"read-only".equals(riskClass)
            || !"secrets-and-pii".equals(redactionClass)
            || maximumDuration == null || maximumDuration.isNegative() || maximumDuration.isZero()
            || maximumDuration.compareTo(Duration.ofSeconds(30)) > 0
            || maximumBytes < 1 || maximumItems < 1 || allowedArgumentNames.isEmpty()
            || egressTargets.isEmpty() || egressTargets.size() > 8
            || egressTargets.stream().anyMatch(target -> !safeEgressTarget(target))) {
            throw new IllegalArgumentException("Tool manifest is incomplete or unsafe.");
        }
    }

    public String registryKey() {
        return tool + ":" + action + ":" + schemaVersion;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean safeEgressTarget(String target) {
        if (blank(target) || target.length() > 253 || target.contains("*")
            || target.contains("\\") || target.contains("\r") || target.contains("\n")) {
            return false;
        }
        try {
            URI uri = new URI(target);
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getHost() == null) return false;
            if (uri.getScheme() == null) return false;
            String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if ("fixture".equals(scheme)) {
                return uri.getPath() == null || uri.getPath().isEmpty();
            }
            if (!"https".equals(scheme)
                && !("http".equals(scheme) && host.endsWith(".opsmind.internal"))) {
                return false;
            }
            if (uri.getPath() != null && !uri.getPath().isEmpty()
                && !"/".equals(uri.getPath())) return false;
            return !host.equals("localhost") && !host.equals("0.0.0.0")
                && !host.equals("127.0.0.1") && !host.equals("::1")
                && !host.startsWith("127.") && !host.startsWith("169.254.")
                && !host.startsWith("10.") && !host.startsWith("192.168.")
                && !host.startsWith("172.16.") && !host.startsWith("172.17.")
                && !host.startsWith("172.18.") && !host.startsWith("172.19.")
                && !host.startsWith("172.2") && !host.startsWith("172.3");
        }
        catch (URISyntaxException exception) {
            return false;
        }
    }
}
