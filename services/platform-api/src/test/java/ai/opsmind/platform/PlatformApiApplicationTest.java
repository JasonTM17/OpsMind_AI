package ai.opsmind.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import ai.opsmind.platform.incident.CreateIncidentRequest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformApiApplicationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void healthEndpointIsReadyWithoutComponentDetails() throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        HttpResponse<String> response = client.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
            .contains("\"status\":\"UP\"")
            .doesNotContain("\"components\"")
            .doesNotContain("\"details\"");
    }

    @Test
    void apiFailsClosedWithoutConfiguredOidc() throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("content-type").orElse(""))
            .startsWith("application/problem+json");
        assertThat(response.headers().firstValue("x-correlation-id")).isPresent();
        assertThat(response.body())
            .contains("identity.unauthenticated")
            .doesNotContain("Exception")
            .doesNotContain("stackTrace");
    }

    @Test
    void incidentRouteFailsClosedWithoutConfiguredOidc() throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port
                + "/api/v1/organizations/11111111-1111-4111-8111-111111111111"
                + "/projects/22222222-2222-4222-8222-222222222222/incidents"))
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .header("Content-Type", "application/json")
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("content-type").orElse(""))
            .startsWith("application/problem+json");
        assertThat(response.body())
            .contains("identity.unauthenticated")
            .doesNotContain("stackTrace")
            .doesNotContain("exception");
    }

    @Test
    void productionJsonMapperRejectsUnknownRequestProperties() {
        assertThat(jsonMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        assertThatThrownBy(() -> jsonMapper.readValue("""
            {
              "title":"API down",
              "summary":"5xx spike",
              "severity":"SEV1",
              "reason":"alert",
              "tenantId":"caller-controlled"
            }
            """, CreateIncidentRequest.class))
            .isInstanceOf(UnrecognizedPropertyException.class);
    }
}
