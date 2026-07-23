package ai.opsmind.platform.investigation.integration;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

final class ToolGatewayHttpClientFactory {

    private ToolGatewayHttpClientFactory() { }

    static HttpClient create(ToolGatewayClientProperties properties) {
        return HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new DirectProxySelector())
            .build();
    }

    private static final class DirectProxySelector extends ProxySelector {

        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null) throw new IllegalArgumentException("Proxy target URI is required.");
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException exception) {
            // Direct connections have no proxy state to update.
        }
    }
}
