package ai.opsmind.toolgateway.connectors.prometheus;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

final class PrometheusHttpClientFactory {

    private PrometheusHttpClientFactory() { }

    static HttpClient create(PrometheusConnectorProperties properties) {
        return HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new DirectProxySelector())
            .build();
    }

    private static final class DirectProxySelector extends ProxySelector {

        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null) throw new IllegalArgumentException("HTTP target URI is required.");
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException exception) {
            // Direct connections have no proxy state to update.
        }
    }
}
