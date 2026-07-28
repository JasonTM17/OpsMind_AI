package ai.opsmind.platform.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsmind.dispatcher.datasource")
public final class DispatcherDataSourceProperties {

    private String url = "disabled";
    private String username = "disabled";
    private String password = "";
    private int maximumPoolSize = 4;
    private long connectionTimeoutMs = 3_000;

    public void validate() {
        if (url == null || !url.startsWith("jdbc:postgresql://")
            || !"opsmind_dispatcher".equals(username)
            || password == null || password.isBlank()
            || maximumPoolSize < 1 || maximumPoolSize > 10
            || connectionTimeoutMs < 250 || connectionTimeoutMs > 30_000) {
            throw new IllegalStateException("Dispatcher datasource configuration is outside policy.");
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

    @Override
    public String toString() {
        return "DispatcherDataSourceProperties{urlConfigured="
            + (url != null && !"disabled".equals(url))
            + ", username='" + username + "', password=<redacted>, maximumPoolSize="
            + maximumPoolSize + ", connectionTimeoutMs=" + connectionTimeoutMs + "}";
    }
}
