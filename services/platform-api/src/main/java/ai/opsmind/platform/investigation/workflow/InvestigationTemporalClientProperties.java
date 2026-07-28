package ai.opsmind.platform.investigation.workflow;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsmind.investigation.temporal-client")
public record InvestigationTemporalClientProperties(
    String clusterId,
    String target,
    boolean tlsEnabled,
    boolean allowLocalCleartext,
    Duration rpcTimeout,
    String requiredWorkerIdentity,
    String requiredWorkerBuildId
) {
    private static final String NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]*";

    public InvestigationTemporalClientProperties {
        clusterId = defaultValue(clusterId, "disabled");
        target = defaultValue(target, "disabled");
        rpcTimeout = rpcTimeout == null ? Duration.ofSeconds(5) : rpcTimeout;
        requiredWorkerIdentity = defaultValue(requiredWorkerIdentity, "disabled");
        requiredWorkerBuildId = defaultValue(requiredWorkerBuildId, "disabled");
    }

    public void validate(InvestigationWorkflowProperties workflow) {
        workflow.validateStartTarget();
        if (!clusterId.equals(workflow.clusterId())
            || target.length() > 255 || target.contains("://") || !target.contains(":")
            || rpcTimeout.compareTo(Duration.ofMillis(100)) < 0
            || rpcTimeout.compareTo(Duration.ofSeconds(30)) > 0
            || !validName(requiredWorkerIdentity, 160)
            || !validName(requiredWorkerBuildId, 128)
            || (!tlsEnabled && !validLocalCleartextTarget())) {
            throw new IllegalStateException("Temporal client configuration is outside policy.");
        }
    }

    private boolean validLocalCleartextTarget() {
        return allowLocalCleartext
            && (target.startsWith("127.0.0.1:") || target.startsWith("localhost:"));
    }

    private static boolean validName(String value, int maximumLength) {
        return value.length() <= maximumLength && value.matches(NAME_PATTERN)
            && !"disabled".equals(value);
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
