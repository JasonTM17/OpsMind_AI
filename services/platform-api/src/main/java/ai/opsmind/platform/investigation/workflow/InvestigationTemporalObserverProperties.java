package ai.opsmind.platform.investigation.workflow;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsmind.investigation.temporal-observer")
public final class InvestigationTemporalObserverProperties {

    private static final String NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]*";

    private String clusterId = "disabled";
    private String target = "disabled";
    private boolean tlsEnabled = true;
    private boolean allowLocalCleartext;
    private Duration rpcTimeout = Duration.ofSeconds(5);
    private String apiKey = "";

    public void validate(InvestigationWorkflowProperties workflow) {
        workflow.validateStartTarget();
        if (!clusterId.equals(workflow.clusterId())
            || !clusterId.matches(NAME_PATTERN)
            || target.length() > 255 || target.contains("://") || !target.contains(":")
            || rpcTimeout.compareTo(Duration.ofMillis(100)) < 0
            || rpcTimeout.compareTo(Duration.ofSeconds(30)) > 0
            || (!tlsEnabled && !validLocalCleartextTarget())
            || (tlsEnabled && apiKey.isBlank())) {
            throw new IllegalStateException("Temporal observer configuration is outside policy.");
        }
    }

    private boolean validLocalCleartextTarget() {
        return allowLocalCleartext
            && (target.startsWith("127.0.0.1:") || target.startsWith("localhost:"));
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = normalize(clusterId);
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = normalize(target);
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public void setTlsEnabled(boolean tlsEnabled) {
        this.tlsEnabled = tlsEnabled;
    }

    public void setAllowLocalCleartext(boolean allowLocalCleartext) {
        this.allowLocalCleartext = allowLocalCleartext;
    }

    public Duration getRpcTimeout() {
        return rpcTimeout;
    }

    public void setRpcTimeout(Duration rpcTimeout) {
        this.rpcTimeout = rpcTimeout == null ? Duration.ofSeconds(5) : rpcTimeout;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    @Override
    public String toString() {
        return "InvestigationTemporalObserverProperties{clusterId='" + clusterId
            + "', targetConfigured=" + !"disabled".equals(target)
            + ", tlsEnabled=" + tlsEnabled + ", apiKey=<redacted>}";
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "disabled" : value.strip();
    }
}
