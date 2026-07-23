package ai.opsmind.toolgateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import ai.opsmind.toolgateway.api.GatewayCallerDeniedException;
import ai.opsmind.toolgateway.api.ToolExecutionController;
import ai.opsmind.toolgateway.application.ToolExecutionService;
import ai.opsmind.toolgateway.config.GatewaySettings;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayWorkloadBoundaryTest {

    @LocalServerPort
    private int port;

    @Test
    void rejectsAnonymousCallerWithStableProblemDetails() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/internal/v1/tools/execute"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(value ->
            assertThat(value).startsWith("application/problem+json"));
        assertThat(response.body()).contains("\"code\":\"caller.unauthenticated\"");
    }

    @Test
    void rejectsAuthenticatedNonPlatformWorkloadBeforeCapabilityVerification() {
        GatewaySettings settings = new GatewaySettings(
            URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway",
            "opsmind-platform-api",
            null,
            URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway-workload",
            "tool.execute",
            null,
            Duration.ofMinutes(5),
            65_536,
            262_144
        );
        ToolExecutionController controller = new ToolExecutionController(
            mock(ToolExecutionService.class),
            settings
        );
        var aiRuntime = UsernamePasswordAuthenticationToken.authenticated(
            "opsmind-ai-runtime",
            "not-used",
            java.util.List.of()
        );

        assertThatThrownBy(() -> controller.execute(aiRuntime, "not-evaluated", null))
            .isInstanceOf(GatewayCallerDeniedException.class);
    }
}
