package ai.opsmind.platform.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsmind.workflow-reconciler.datasource")
public final class WorkflowReconcilerDataSourceProperties {

    private String url = "disabled";
    private String username = "disabled";
    private String password = "";
    private int maximumPoolSize = 2;
    private long connectionTimeoutMs = 3_000;
    private int queryTimeoutSeconds = 1;

    public void validate() {
        if (url == null || !url.startsWith("jdbc:postgresql://")
            || !"opsmind_workflow_reconciler".equals(username)
            || password == null || password.isBlank()
            || maximumPoolSize < 1 || maximumPoolSize > 4
            || connectionTimeoutMs < 250 || connectionTimeoutMs > 30_000
            || queryTimeoutSeconds < 1 || queryTimeoutSeconds > 30) {
            throw new IllegalStateException(
                "Workflow reconciler datasource configuration is outside policy."
            );
        }
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public String toString() {
        return "WorkflowReconcilerDataSourceProperties{urlConfigured="
            + (url != null && !"disabled".equals(url))
            + ", username='" + username + "', password=<redacted>, maximumPoolSize="
            + maximumPoolSize + ", connectionTimeoutMs=" + connectionTimeoutMs
            + ", queryTimeoutSeconds=" + queryTimeoutSeconds + "}";
    }
}
