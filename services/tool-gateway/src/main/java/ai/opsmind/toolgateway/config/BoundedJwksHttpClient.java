package ai.opsmind.toolgateway.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/** Creates a no-redirect JWKS client with trust-boundary timeouts. */
final class BoundedJwksHttpClient {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private BoundedJwksHttpClient() { }

    static RestOperations create() {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(TIMEOUT);
        return new RestTemplate(requestFactory);
    }
}
