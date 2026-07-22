package ai.opsmind.toolgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ToolGatewayApplicationTest {

    @LocalServerPort
    private int port;

    @Test
    void healthEndpointIsReadyWithoutComponentDetails() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
            .contains("\"status\":\"UP\"")
            .doesNotContain("\"components\"")
            .doesNotContain("\"details\"");
    }

    @Test
    void readinessFailsClosedWhileDurableAdaptersAreUnavailable() throws Exception {
        HttpResponse<String> response = get("/ready");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"status\":\"DOWN\"");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        return client.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );
    }
}
